package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.domain.model.Game
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assigns multi-disc set identity to a batch of freshly scanned games (docs/plans/multi-disc-games-plan.md
 * steps 1–3). Runs over the games of one scan pass, so a full first scan of a folder groups every
 * disc together; games with no disc tag and no linking playlist keep a NULL [Game.discSetKey] and
 * are untouched.
 *
 * Set membership:
 *  - a game whose raw filename carries a disc tag (see [parseDiscTag]) joins the set keyed by
 *    platform + containing folder + disc-stripped/region-stripped/revision-stripped title
 *  - an `.m3u` whose playlist entries resolve to scanned games becomes that set's primary row
 *    (disc number NULL — the emulator handles disc swapping from the playlist), and the listed
 *    discs join the same set as non-primary rows; entries without a disc tag take playlist order
 *  - every set gets exactly one primary: the `.m3u` when present, otherwise the lowest-numbered
 *    disc (ties broken by path for determinism)
 *
 * The containing folder is part of the key so two dumps of the same game in different folders do
 * not merge (over-merging is worse than under-merging — see the plan's Risks).
 */
@Singleton
class DiscSetBuilder @Inject constructor() {

    /**
     * Reads a game's `.m3u` playlist entries (raw lines). Reading differs by scan path — raw-path
     * scans read the file, SAF scans read the document URI — so it is left to the caller (and to
     * tests). Return null when the game is unreadable / not a playlist.
     */
    fun interface M3uReader {
        fun read(game: Game): List<String>?
    }

    private data class Candidate(
        val game: Game,
        val stem: String,     // raw filename stem, disc tag still present
        val folder: String,   // parent folder ("" when unknown)
        val ext: String,      // lowercase, no dot
        val basename: String, // lowercase raw filename
    )

    private data class Assignment(
        val key: String,
        val discNumber: Int?,   // null only for the set's .m3u primary
        val viaM3u: Boolean,
    )

    /**
     * Returns the games with disc-set fields populated, preserving the input order. Games that are
     * not part of a set are returned unchanged. Runs over the games of one scan pass (see
     * [reconcile] for joining those games into sets whose other members were scanned earlier).
     */
    fun assign(games: List<Game>, m3uReader: M3uReader): List<Game> = derive(games, m3uReader)

    /**
     * Re-derives set identity over a batch that mixes already-scanned rows with newly added ones
     * (an incremental scan: a new disc arriving into an already-scanned `.m3u` set, a new `.m3u`
     * adopting existing discs, or a lower-numbered disc that must take the primary). Returns only
     * the rows whose disc fields changed, so the caller upserts just those. The derivation is
     * deterministic, so reconcile is a no-op on a fully correct batch — stored values are never
     * trusted, they are only diffed against.
     */
    fun reconcile(games: List<Game>, m3uReader: M3uReader): List<Game> {
        val derived = derive(games, m3uReader)
        return games.zip(derived)
            .filter { (before, after) ->
                before.discSetKey != after.discSetKey ||
                    before.discNumber != after.discNumber ||
                    before.isDiscPrimary != after.isDiscPrimary
            }
            .map { it.second }
    }

    private fun derive(games: List<Game>, m3uReader: M3uReader): List<Game> {
        if (games.isEmpty()) return games

        val candidates = games.mapNotNull { game -> game.candidate() }
        if (candidates.isEmpty()) return games

        val byPath = candidates.associateBy { it.game.romPath!! }
        val byBasename = candidates.groupBy { it.basename }
        val byFolderBasename = candidates.groupBy { it.folder to it.basename }

        // romPath -> assignment. Tagged games get their key first; an m3u then overrides the key
        // of every disc it resolves (the playlist is the set, so it owns the identity).
        val assignments = HashMap<String, Assignment>()

        // Step A — disc-tagged games form tentative sets (disc 1 … N, no primary yet).
        for (c in candidates) {
            val tag = parseDiscTag(c.stem) ?: continue
            assignments[c.game.romPath!!] = Assignment(
                key = setKey(c, keyTitleFor(c, tag)),
                discNumber = tag.discNumber,
                viaM3u = false,
            )
        }

        // Step B — .m3u playlists: the playlist becomes the set's primary and pulls in every disc
        // it lists. A playlist whose entries resolve to nothing scanned creates no set.
        for (m3u in candidates.filter { it.ext == "m3u" }) {
            val entries = m3uReader.read(m3u.game) ?: continue
            val resolved = mutableListOf<Candidate>()
            for (entry in entries) {
                val name = playlistEntryName(entry) ?: continue
                val match = (byFolderBasename[m3u.folder to name] ?: byBasename[name])
                    ?.firstOrNull { it !== m3u }
                if (match != null) resolved.add(match)
            }
            if (resolved.isEmpty()) continue

            val m3uKey = setKey(m3u, keyTitleFor(m3u, null))
            assignments[m3u.game.romPath!!] = Assignment(m3uKey, discNumber = null, viaM3u = true)
            resolved.forEachIndexed { index, disc ->
                val tag = parseDiscTag(disc.stem)
                assignments[disc.game.romPath!!] = Assignment(
                    key = m3uKey,
                    discNumber = tag?.discNumber ?: (index + 1),
                    viaM3u = true,
                )
            }
        }

        if (assignments.isEmpty()) return games

        // Step C — one primary per set: the .m3u when present, else the lowest disc number.
        val primaryByKey = assignments.entries
            .groupBy { it.value.key }
            .mapNotNull { (key, members) ->
                val primary = members.firstOrNull { it.value.viaM3u && it.value.discNumber == null }
                    ?: members.minWithOrNull(
                        compareBy({ it.value.discNumber ?: Int.MAX_VALUE }, { it.key }),
                    )
                primary?.let { key to it.key }
            }
            .toMap()

        // Step D — enrich the input games, keeping order.
        return games.map { game ->
            val path = game.romPath
            val assignment = if (path != null) assignments[path] else null
            if (assignment == null) {
                game
            } else {
                game.copy(
                    discSetKey = assignment.key,
                    discNumber = assignment.discNumber,
                    isDiscPrimary = primaryByKey[assignment.key] == path,
                )
            }
        }
    }

    private fun Game.candidate(): Candidate? {
        val path = romPath ?: return null
        val basename = path.substringAfterLast('/').substringAfterLast('\\')
        if (basename.isBlank() || basename == path) return null
        return Candidate(
            game = this,
            stem = basename.substringBeforeLast('.', basename),
            folder = path.substringBeforeLast('/').substringBeforeLast('\\'),
            ext = basename.substringAfterLast('.', "").lowercase(),
            basename = basename.lowercase(),
        )
    }

    private fun keyTitleFor(c: Candidate, tag: DiscTag?): String =
        if (tag != null) cleanRomTitle(tag.strippedTitle) else cleanRomTitle(c.stem)

    // platform + folder + disc-stripped/region-stripped/revision-stripped title. "\u0001" cannot
    // appear in a path or title, so the parts can never collide.
    private fun setKey(c: Candidate, keyTitle: String): String =
        "${c.game.platformId}\u0001${c.folder}\u0001$keyTitle"

    private fun playlistEntryName(line: String): String? {
        var entry = line.trim()
        if (entry.isEmpty() || entry.startsWith("#")) return null
        entry = entry.removePrefix("\"").removeSuffix("\"").removePrefix("./")
        if (entry.isBlank()) return null
        val name = entry.substringAfterLast('/').substringAfterLast('\\')
        return name.lowercase().takeIf { it.isNotBlank() }
    }
}
