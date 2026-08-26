package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameRegion
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
 * not merge (over-merging is worse than under-merging — see the plan's Risks). The folder's
 * trailing segment is cleaned with the same rules as the title (see [cleanRomTitle]): a disc tag
 * is stripped ("one folder per disc": `<Game> (Disc 1)/`, `<Game> (Disc 2)/`) and region/revision
 * tags are removed, because those sibling folders are one set, not two dumps — and the region tag
 * on one disc's folder may be missing from its sibling's (`Parasite Eve II (USA) (Disc 1)/` next
 * to `Parasite Eve II (Disc 2)/`). Structurally different folders (NA/ vs EU/) survive the
 * cleaning and still keep dumps apart.
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

    /**
     * Detects a game's region from the disc image content. Same per-path variation as [M3uReader]
     * (raw vs SAF), so it is also left to the caller (and to tests). Null means "not detected" —
     * an unknown region never splits a set, it only falls back to merging.
     */
    fun interface RegionReader {
        fun read(game: Game): GameRegion?
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
    fun assign(
        games: List<Game>,
        regionReader: RegionReader = RegionReader { null },
        m3uReader: M3uReader,
    ): List<Game> = derive(games, m3uReader, regionReader)

    /**
     * Re-derives set identity over a batch that mixes already-scanned rows with newly added ones
     * (an incremental scan: a new disc arriving into an already-scanned `.m3u` set, a new `.m3u`
     * adopting existing discs, or a lower-numbered disc that must take the primary). Returns only
     * the rows whose disc fields (or detected region) changed, so the caller upserts just those.
     * The derivation is deterministic, so reconcile is a no-op on a fully correct batch — stored
     * values are never trusted, they are only diffed against.
     */
    fun reconcile(
        games: List<Game>,
        regionReader: RegionReader = RegionReader { null },
        m3uReader: M3uReader,
    ): List<Game> {
        val derived = derive(games, m3uReader, regionReader)
        return games.zip(derived)
            .filter { (before, after) ->
                before.discSetKey != after.discSetKey ||
                    before.discNumber != after.discNumber ||
                    before.isDiscPrimary != after.isDiscPrimary ||
                    before.region != after.region
            }
            .map { it.second }
    }

    private fun derive(games: List<Game>, m3uReader: M3uReader, regionReader: RegionReader): List<Game> {
        if (games.isEmpty()) return games

        // Region is detected from the disc image (never the filename), read once per path within
        // the batch. A fresh detection wins; an unreadable file falls back to the stored value so
        // a transient read failure never wipes a known region.
        val regionByPath = HashMap<String, GameRegion?>()
        fun regionOf(game: Game): GameRegion? = game.romPath?.let { path ->
            regionByPath.getOrPut(path) { regionReader.read(game) ?: game.region }
        }

        val candidates = games.mapNotNull { game -> game.candidate() }
        if (candidates.isEmpty()) return games

        val byPath = candidates.associateBy { it.game.romPath!! }
        val byBasename = candidates.groupBy { it.basename }
        val byFolderBasename = candidates.groupBy { it.folder to it.basename }

        // romPath -> assignment. Tagged games get their key first; an m3u then overrides the key
        // of every disc it resolves (the playlist is the set, so it owns the identity).
        val assignments = HashMap<String, Assignment>()

        // Step A — disc-tagged games form tentative sets (disc 1 … N, no primary yet). Region
        // (detected from the disc image, never the filename) refines membership: sibling disc
        // folders whose images genuinely disagree on region are two dumps, so each region becomes
        // its own set. The split only fires when EVERY member carries a known region — an unknown
        // (unreadable, or a compressed container like .chd) disc keeps the group merged rather
        // than breaking a set on a detection gap.
        val tagged = ArrayList<Triple<Candidate, DiscTag, String>>()
        for (c in candidates) {
            val tag = parseDiscTag(c.stem) ?: continue
            tagged.add(Triple(c, tag, setKey(c, keyTitleFor(c, tag))))
        }
        val regionSplitKey = HashMap<String, String>()  // romPath -> region-appended key
        tagged.groupBy { it.third }.forEach { (baseKey, members) ->
            val regions = members.mapNotNull { regionOf(it.first.game) }
            if (regions.size == members.size && regions.distinct().size > 1) {
                for ((c, _, _) in members) {
                    regionSplitKey[c.game.romPath!!] = "$baseKey\u0001${regionOf(c.game)!!.name}"
                }
            }
        }
        for ((c, tag, baseKey) in tagged) {
            assignments[c.game.romPath!!] = Assignment(
                key = regionSplitKey[c.game.romPath!!] ?: baseKey,
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
                // A previously linked playlist can become unreadable, disappear, or stop listing
                // this row. Do not leave stale primary/set fields behind; otherwise a missing m3u
                // can continue to project itself over the real disc rows.
                if (game.discSetKey != null || game.discNumber != null || game.isDiscPrimary) {
                    game.copy(region = regionOf(game), discSetKey = null, discNumber = null, isDiscPrimary = false)
                } else {
                    game.copy(region = regionOf(game))
                }
            } else {
                game.copy(
                    region = regionOf(game),
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

    // The containing folder participates in the set key so two unrelated dumps of the same title
    // in different folders never merge. But the common "one folder per disc" layout puts each disc
    // in `<Game> (Disc 1)/`, `<Game> (Disc 2)/`, … — names that differ only by the disc tag. Those
    // are ONE set, not two dumps, so the folder's trailing segment is cleaned like a title before
    // it feeds the key: the disc tag is stripped, and region/revision tags are removed too — a
    // `(USA)` on one disc's folder is often missing from its sibling's (`Parasite Eve II (USA)
    // (Disc 1)/` beside `Parasite Eve II (Disc 2)/`), and that inconsistency must not split the
    // set. The parent path is left verbatim, so structurally different folders (NA/, EU/, a demo
    // vs a full release) still keep the key apart.
    private fun Candidate.discNormalizedFolder(): String {
        val slash = folder.lastIndexOf('/')
        val backslash = folder.lastIndexOf('\\')
        val sep = maxOf(slash, backslash)
        if (sep < 0) return cleanedFolderSegment(folder)
        val parent = folder.substring(0, sep)
        val segment = folder.substring(sep + 1)
        return "$parent${folder[sep]}${cleanedFolderSegment(segment)}"
    }

    private fun cleanedFolderSegment(segment: String): String {
        val discStripped = parseDiscTag(segment)?.strippedTitle ?: segment
        return cleanRomTitle(discStripped).ifBlank { discStripped }
    }

    private fun keyTitleFor(c: Candidate, tag: DiscTag?): String =
        if (tag != null) cleanRomTitle(tag.strippedTitle) else cleanRomTitle(c.stem)

    // platform + disc-normalized folder + disc-stripped/region-stripped/revision-stripped title.
    // "\u0001" cannot appear in a path or title, so the parts can never collide.
    private fun setKey(c: Candidate, keyTitle: String): String =
        "${c.game.platformId}\u0001${c.discNormalizedFolder()}\u0001$keyTitle"

    private fun playlistEntryName(line: String): String? {
        var entry = line.trim()
        if (entry.isEmpty() || entry.startsWith("#")) return null
        entry = entry.removePrefix("\"").removeSuffix("\"").removePrefix("./")
        if (entry.isBlank()) return null
        val name = entry.substringAfterLast('/').substringAfterLast('\\')
        return name.lowercase().takeIf { it.isNotBlank() }
    }
}
