package com.playfieldportal.feature.artwork.importer

import com.playfieldportal.feature.artwork.importer.ArtworkImportMatcher.GameRef
import com.playfieldportal.feature.artwork.importer.ArtworkImportMatcher.PlatformIndex
import com.playfieldportal.feature.artwork.importer.ArtworkImportMatcher.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkImportMatcherTest {

    private val index = PlatformIndex(
        listOf(
            GameRef(1, "Ratchet & Clank (USA)", "Ratchet & Clank", null),
            GameRef(2, "SLUS-97199", "Jak and Daxter: The Precursor Legacy", null),
            GameRef(3, "Final Fantasy VII (Disc 1) (USA)", "Final Fantasy VII", null),
            GameRef(4, "Final Fantasy VII (Disc 2) (USA)", "Final Fantasy VII", null),
            GameRef(5, "Crash Bandicoot (Europe)", "Crash Bandicoot", "Crash Bandicoot"),
        )
    )

    @Test
    fun `pass 1 matches exact rom filename ignoring case and spacing`() {
        val result = index.match("ratchet & clank (usa).png")
        assertEquals(Result.Matched(1, MatchConfidence.EXACT_FILENAME), result)
    }

    @Test
    fun `pass 2 matches display title when rom is serial-named`() {
        val result = index.match("Jak and Daxter: The Precursor Legacy.png")
        assertEquals(Result.Matched(2, MatchConfidence.DISPLAY_TITLE), result)
    }

    @Test
    fun `pass 3 matches simplified title only when unique`() {
        // "Crash Bandicoot (USA)" ≠ stored "(Europe)" stem, but simplifies uniquely.
        val result = index.match("Crash Bandicoot (USA).png")
        assertEquals(Result.Matched(5, MatchConfidence.SIMPLIFIED_TITLE), result)
    }

    @Test
    fun `multi-disc artwork with no disc tag is ambiguous, never guessed`() {
        val result = index.match("Final Fantasy VII.png")
        assertTrue(result is Result.Ambiguous)
        assertEquals(setOf(3L, 4L), (result as Result.Ambiguous).gameIds.toSet())
    }

    @Test
    fun `disc-tagged artwork matches its exact disc`() {
        val result = index.match("Final Fantasy VII (Disc 2) (USA).png")
        assertEquals(Result.Matched(4, MatchConfidence.EXACT_FILENAME), result)
    }

    @Test
    fun `unknown artwork is unmatched`() {
        assertEquals(Result.Unmatched, index.match("Some Game Nobody Owns.png"))
    }

    @Test
    fun `same-stem duplicate rows (cue plus bin) match all rows, not ambiguous`() {
        val dupIndex = PlatformIndex(
            listOf(
                GameRef(10, "Parasite Eve II (USA) (Disc 1)", "Parasite Eve II", null),   // .cue row
                GameRef(11, "Parasite Eve II (USA) (Disc 1)", "Parasite Eve II", null),   // .bin row
            )
        )
        val result = dupIndex.match("Parasite Eve II (USA) (Disc 1).png")
        assertEquals(
            Result.Matched(listOf(10L, 11L), MatchConfidence.EXACT_FILENAME),
            result,
        )
    }

    @Test
    fun `dump-index prefixed artwork matches at degraded confidence`() {
        val result = index.match("0556 - Ratchet & Clank (USA).png")
        assertEquals(Result.Matched(listOf(1L), MatchConfidence.INDEXED_FILENAME), result)
    }

    @Test
    fun `index prefix stripping does not fire on titles that legitimately start with numbers`() {
        val numIndex = PlatformIndex(
            listOf(GameRef(20, "1942 (Japan, USA)", "1942", null))
        )
        // Exact match works; and a genuine "1942 - something" title isn't destroyed because
        // pass 4 only runs when earlier passes found nothing.
        assertEquals(
            Result.Matched(listOf(20L), MatchConfidence.EXACT_FILENAME),
            numIndex.match("1942 (Japan, USA).png"),
        )
    }

    // ── Pass 5: canonical title (C22 task T2) ─────────────────────────────────

    @Test
    fun `pass 5 reconnects the No-Intro trailing-article convention`() {
        val zelda = PlatformIndex(
            listOf(GameRef(30, null, "The Legend of Zelda: Ocarina of Time", null))
        )
        assertEquals(
            Result.Matched(listOf(30L), MatchConfidence.CANONICAL_TITLE),
            zelda.match("Legend of Zelda, The - Ocarina of Time (USA).png"),
        )
    }

    @Test
    fun `pass 5 matches a roman-numeral filename to an arabic-numeral title`() {
        val ff = PlatformIndex(
            listOf(GameRef(31, null, "Final Fantasy 7", null))
        )
        assertEquals(
            Result.Matched(listOf(31L), MatchConfidence.CANONICAL_TITLE),
            ff.match("Final Fantasy VII.png"),
        )
    }

    @Test
    fun `two games canonicalizing alike are ambiguous, never matched`() {
        // Two rows for one game, imported under both spellings of the article convention. The
        // query's simplified form ("legend of zelda") equals neither row's simplified form
        // ("the legend of zelda", "legend of zelda the"), so passes 1-3 all miss and pass 5 is
        // the one that fires — where the uniqueness valve refuses to guess between them.
        val zelda = PlatformIndex(
            listOf(
                GameRef(32, null, "The Legend of Zelda", null),
                GameRef(33, null, "Legend of Zelda, The", null),
            )
        )
        val result = zelda.match("Legend of Zelda.png")
        assertTrue(result is Result.Ambiguous)
        assertEquals(setOf(32L, 33L), (result as Result.Ambiguous).gameIds.toSet())
    }

    @Test
    fun `pass 5 does not rescue a title pass 3 already found ambiguous`() {
        // Both rows simplify to "final fantasy vii", so pass 3 returns Ambiguous and pass 5 is
        // never reached — a looser pass must not turn a genuine ambiguity into a match.
        val result = index.match("Final Fantasy VII.png")
        assertTrue(result is Result.Ambiguous)
        assertEquals(setOf(3L, 4L), (result as Result.Ambiguous).gameIds.toSet())
    }

    @Test
    fun `pass 4 inherits pass 5 and still degrades the confidence`() {
        val zelda = PlatformIndex(
            listOf(GameRef(34, null, "The Legend of Zelda", null))
        )
        assertEquals(
            Result.Matched(listOf(34L), MatchConfidence.INDEXED_FILENAME),
            zelda.match("0556 - Legend of Zelda, The.png"),
        )
    }

    @Test
    fun `confidence order is the priority order ArtworkImportPlanner compares by ordinal`() {
        // ArtworkImportPlanner keeps the best match per kind with
        // `item.confidence.ordinal < existing.confidence.ordinal`, so this ordering is behaviour,
        // not documentation. CANONICAL_TITLE must sit below SIMPLIFIED_TITLE and above
        // INDEXED_FILENAME.
        assertEquals(
            listOf(
                MatchConfidence.EXACT_FILENAME,
                MatchConfidence.DISPLAY_TITLE,
                MatchConfidence.SIMPLIFIED_TITLE,
                MatchConfidence.CANONICAL_TITLE,
                MatchConfidence.INDEXED_FILENAME,
            ),
            MatchConfidence.entries.sortedBy { it.ordinal },
        )
    }
}
