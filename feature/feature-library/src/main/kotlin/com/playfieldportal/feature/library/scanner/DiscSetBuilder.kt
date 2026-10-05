package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameRegion
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assigns multi-disc set identity to a batch of freshly scanned games.
 * Runs over the games of one scan pass, so a full first scan of a folder groups every
 * disc together; games with no disc tag and no linking playlist keep a NULL [Game.discSetKey] and
 * are untouched.
 *
 * Set membership:
 *  - a game whose raw filename carries a disc tag (see [parseDiscTag]) joins the set keyed by
 *    platform + containing folder + disc-stripped/region-stripped/revision-stripped title
 *  - an `.m3u` whose playlist entries resolve to scanned games becomes that set's primary row
 *    (disc number NULL — the emulator handles disc swapping from the playlist), and the listed
 *    discs join the same set as non-primary rows; entries without a disc tag take playlist order
 *  - every set gets exactly one primary: the disc the user picked with Choose Disc
 *    ([Game.isDiscPreferred]), else the `.m3u` when present, otherwise the lowest-numbered disc
 *    that is not missing (ties broken by path for determinism)
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

    /**
     * Reads a `.cue` / `.gdi` game's raw sheet lines, so rows for the files that sheet lists can be
     * recognised as its companions. Same per-path variation as [M3uReader]. Null means unreadable —
     * nothing is then treated as a companion of that sheet.
     */
    fun interface SheetReader {
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
    fun assign(
        games: List<Game>,
        regionReader: RegionReader = RegionReader { null },
        m3uReader: M3uReader,
    ): List<Game> = derive(games, m3uReader, regionReader, SheetReader { null })

    /**
     * Re-derives set identity over a batch that mixes already-scanned rows with newly added ones
     * (an incremental scan: a new disc arriving into an already-scanned `.m3u` set, a new `.m3u`
     * adopting existing discs, or a lower-numbered disc that must take the primary). Returns only
     * the rows whose disc fields (or detected region) changed, so the caller upserts just those.
     * The derivation is deterministic, so reconcile is a no-op on a fully correct batch — stored
     * values are never trusted, they are only diffed against.
     *
     * [sheetReader] exists for the stored rows only: a scan never emits a sheet's companion files,
     * but a library scanned before that suppression existed still holds a row per `.bin`. Those
     * rows are not discs, so they are taken out of the set here (see [companionPaths]).
     */
    fun reconcile(
        games: List<Game>,
        regionReader: RegionReader = RegionReader { null },
        sheetReader: SheetReader = SheetReader { null },
        m3uReader: M3uReader,
    ): List<Game> {
        val derived = derive(games, m3uReader, regionReader, sheetReader)
        return games.zip(derived)
            .filter { (before, after) ->
                before.discSetKey != after.discSetKey ||
                    before.discNumber != after.discNumber ||
                    before.isDiscPrimary != after.isDiscPrimary ||
                    before.isDiscPreferred != after.isDiscPreferred ||
                    before.region != after.region
            }
            .map { it.second }
    }

    private fun derive(
        games: List<Game>,
        m3uReader: M3uReader,
        regionReader: RegionReader,
        sheetReader: SheetReader,
    ): List<Game> {
        if (games.isEmpty()) return games

        // Region is detected from the disc image (never the filename), read once per path within
        // the batch — INCLUDING an unknown-region answer: the memo keys on presence, not nullness
        // (getOrPut re-runs on a cached null, doubling every 256 KB head read for exactly the
        // discs whose detection is already failing). A fresh detection wins; an unreadable file
        // falls back to the stored value so a transient read failure never wipes a known region.
        val regionByPath = HashMap<String, GameRegion?>()
        fun regionOf(game: Game): GameRegion? = game.romPath?.let { path ->
            if (regionByPath.containsKey(path)) regionByPath[path]
            else (regionReader.read(game) ?: game.region).also { regionByPath[path] = it }
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
        val companions = companionPaths(candidates, sheetReader)
        val tagged = ArrayList<Triple<Candidate, DiscTag, String>>()
        for (c in candidates) {
            if (c.game.romPath!! in companions) continue
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

        // Step C — one primary per set: the disc the user picked with Choose Disc, else the .m3u
        // when present, else the lowest-numbered disc that is present. The primary is the row the
        // library shows and launches, so a missing disc — picked or not — only leads a set whose
        // discs are all missing (an unplugged card reshuffles nothing). A missing pick keeps its
        // mark, so it takes the primary back when its file returns.
        // Two rows can share a disc number when a sheet's companion could not be ruled out above
        // (its sheet was unreadable); the sheet is the launch file, so raw track data sorts behind
        // it rather than winning on the path tie-break (".bin" < ".cue").
        val missingPaths = candidates.filter { it.game.isMissing }.mapTo(HashSet()) { it.game.romPath!! }
        val preferredPaths = candidates.filter { it.game.isDiscPreferred }.mapTo(HashSet()) { it.game.romPath!! }
        val primaryByKey = assignments.entries
            .groupBy { it.value.key }
            .mapNotNull { (key, members) ->
                val primary = members
                    .filter { it.key in preferredPaths && it.key !in missingPaths }
                    .minByOrNull { it.key }
                    ?: members.firstOrNull { it.value.viaM3u && it.value.discNumber == null }
                    ?: members.minWithOrNull(
                        compareBy(
                            { it.key in missingPaths },
                            { it.value.discNumber ?: Int.MAX_VALUE },
                            { it.key.substringAfterLast('.', "").lowercase() in RAW_TRACK_EXTENSIONS },
                            { it.key },
                        ),
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
                if (game.discSetKey != null || game.discNumber != null || game.isDiscPrimary || game.isDiscPreferred) {
                    game.copy(
                        region = regionOf(game),
                        discSetKey = null,
                        discNumber = null,
                        isDiscPrimary = false,
                        isDiscPreferred = false,
                    )
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

    // The rows that are a sheet's companion files — a `.bin` a sibling `.cue` lists, a track file a
    // `.gdi` references. Content-based and same-folder only, exactly like the scan-time suppression
    // (DiscCompanionSuppressor / DiscImageResolver), so a sheet-less `.bin` dump stays a real disc.
    // A sheet is only read when its folder holds a row that could be its companion.
    private fun companionPaths(candidates: List<Candidate>, sheetReader: SheetReader): Set<String> {
        val companions = HashSet<String>()
        for ((_, inFolder) in candidates.groupBy { it.folder }) {
            val sheets = inFolder.filter { it.ext in SHEET_EXTENSIONS }
            if (sheets.isEmpty() || sheets.size == inFolder.size) continue
            val byBasename = inFolder.associateBy { it.basename }
            for (sheet in sheets) {
                val lines = sheetReader.read(sheet.game) ?: continue
                val referenced = if (sheet.ext == "cue") cueSheetReferences(lines) else gdiSheetTrackNames(lines)
                for (name in referenced) {
                    byBasename[name]?.takeIf { it !== sheet }?.let { companions.add(it.game.romPath!!) }
                }
            }
        }
        return companions
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

    private companion object {
        val SHEET_EXTENSIONS = setOf("cue", "gdi")
        val RAW_TRACK_EXTENSIONS = setOf("bin", "img")
    }
}
