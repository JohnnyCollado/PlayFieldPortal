package com.playfieldportal.feature.artwork.importer

import com.playfieldportal.feature.artwork.match.TitleCanon
import com.playfieldportal.feature.artwork.portable.ArtworkNaming

/**
 * Ties an artwork filename to a game in several passes, per platform. Pure JVM — all lookups are
 * prebuilt hash maps, so matching a 50k-file library is O(candidates + games).
 *
 *  Pass 1 — exact normalized ROM filename stem ("Ratchet & Clank (USA)" == "ratchet & clank (usa)")
 *  Pass 2 — the game's display title (covers SLUS-97199.chd ROMs whose artwork is title-named)
 *  Pass 3 — simplified title (release tags stripped); auto-match only on exactly one candidate
 *  Pass 4 — passes 1–3 again with a leading dump-index prefix ("0556 - Game.png") stripped,
 *           reported at the weakest confidence
 *  Pass 5 — canonical title ([TitleCanon]: leading/trailing "the" dropped, roman numerals made
 *           arabic), which is what reconnects the No-Intro "Legend of Zelda, The" convention to a
 *           naturally-titled game; auto-match only on exactly one candidate, like pass 3
 *
 * Pass-1 multi-hits are NOT ambiguous: ids under one stem key have byte-identical normalized
 * filenames, which only happens when one physical game scanned as several rows (.cue + .bin) —
 * the artwork belongs to all of them. Title passes with several distinct games stay ambiguous
 * and are surfaced for review, never guessed.
 */
object ArtworkImportMatcher {

    data class GameRef(
        val id: Long,
        val romStem: String?,        // ROM filename without extension, null for non-ROM entries
        val displayTitle: String,
        val scrapedTitle: String?,
    )

    sealed interface Result {
        /** [gameIds] has several entries only for same-stem duplicate rows of one game. */
        data class Matched(val gameIds: List<Long>, val confidence: MatchConfidence) : Result {
            constructor(gameId: Long, confidence: MatchConfidence) : this(listOf(gameId), confidence)
        }
        data class Ambiguous(val gameIds: List<Long>) : Result
        data object Unmatched : Result
    }

    // "0556 - Game Name.png" / "0034-Game.png" — No-Intro/dat-o-matic dump-index prefixes.
    private val INDEX_PREFIX = Regex("""^\d{2,6}\s*-\s*""")

    /** Prebuilt lookup maps for one platform's games. */
    class PlatformIndex(games: List<GameRef>) {
        private val byRomStem = games.filter { it.romStem != null }
            .groupBy({ ArtworkNaming.normalizeForMatch(it.romStem!!) }, { it.id })
        private val byTitle = buildMap<String, MutableList<Long>> {
            games.forEach { g ->
                val titles = listOfNotNull(g.displayTitle, g.scrapedTitle)
                    .map { ArtworkNaming.normalizeForMatch(it) }.distinct()
                titles.forEach { getOrPut(it) { mutableListOf() }.add(g.id) }
            }
        }
        private val bySimplified = buildMap<String, MutableList<Long>> {
            games.forEach { g ->
                val keys = listOfNotNull(g.romStem, g.displayTitle, g.scrapedTitle)
                    .map { ArtworkNaming.simplifyTitle(it) }.filter { it.isNotBlank() }.distinct()
                keys.forEach { getOrPut(it) { mutableListOf() }.add(g.id) }
            }
        }
        private val byCanonical = buildMap<String, MutableList<Long>> {
            games.forEach { g ->
                val keys = listOfNotNull(g.romStem, g.displayTitle, g.scrapedTitle)
                    .map { TitleCanon.of(ArtworkNaming.simplifyTitle(it)) }
                    .filter { it.isNotBlank() }.distinct()
                keys.forEach { getOrPut(it) { mutableListOf() }.add(g.id) }
            }
        }

        fun match(artworkFileName: String): Result {
            val stem = ArtworkNaming.fileStem(artworkFileName)
            matchStem(stem)?.let { return it }
            // Pass 4: retry without a dump-index prefix, degraded to the weakest confidence.
            val stripped = stem.replace(INDEX_PREFIX, "")
            if (stripped != stem && stripped.isNotBlank()) {
                matchStem(stripped)?.let { result ->
                    return if (result is Result.Matched) {
                        result.copy(confidence = MatchConfidence.INDEXED_FILENAME)
                    } else result
                }
            }
            return Result.Unmatched
        }

        private fun matchStem(stem: String): Result? {
            // Pass 1: ids under one stem key are duplicate rows of the same game — match them all.
            byRomStem[ArtworkNaming.normalizeForMatch(stem)]?.let {
                return Result.Matched(it.distinct(), MatchConfidence.EXACT_FILENAME)
            }
            byTitle[ArtworkNaming.normalizeForMatch(stem)]?.let {
                return singleOrAmbiguous(it, MatchConfidence.DISPLAY_TITLE)
            }
            val simplified = ArtworkNaming.simplifyTitle(stem)
            if (simplified.isNotBlank()) {
                bySimplified[simplified]?.let { ids ->
                    // Simplified matching is loose — only a unique candidate is safe.
                    return if (ids.distinct().size == 1) {
                        Result.Matched(ids.distinct(), MatchConfidence.SIMPLIFIED_TITLE)
                    } else {
                        Result.Ambiguous(ids.distinct())
                    }
                }
                // Pass 5: canonical-to-canonical. The query's canonical form is always looked up,
                // even when canonicalizing changed nothing about it — "Legend of Zelda.png" is
                // already canonical, and the whole point is that it should reach a game stored as
                // "The Legend of Zelda". A title pass 3 already found genuinely ambiguous has
                // returned above; a looser pass must never rescue it.
                val canonical = TitleCanon.of(simplified)
                if (canonical.isNotBlank()) {
                    byCanonical[canonical]?.let { ids ->
                        return if (ids.distinct().size == 1) {
                            Result.Matched(ids.distinct(), MatchConfidence.CANONICAL_TITLE)
                        } else {
                            Result.Ambiguous(ids.distinct())
                        }
                    }
                }
            }
            return null
        }

        private fun singleOrAmbiguous(ids: List<Long>, confidence: MatchConfidence): Result {
            val distinct = ids.distinct()
            return if (distinct.size == 1) Result.Matched(distinct, confidence)
            else Result.Ambiguous(distinct)
        }
    }
}
