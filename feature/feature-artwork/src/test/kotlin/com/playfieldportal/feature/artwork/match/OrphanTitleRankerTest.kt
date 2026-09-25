package com.playfieldportal.feature.artwork.match

import com.playfieldportal.feature.artwork.match.OrphanTitleRanker.Candidate
import com.playfieldportal.feature.artwork.match.OrphanTitleRanker.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** C22 task T4 — what a typed orphan search means, decided without a ViewModel. */
class OrphanTitleRankerTest {

    private val games = listOf(
        Candidate(1, "The Legend of Zelda: Ocarina of Time", "Legend of Zelda, The - Ocarina of Time (USA)"),
        Candidate(2, "Final Fantasy 7", "Final Fantasy VII (Disc 1) (USA)"),
        Candidate(3, "Jak and Daxter: The Precursor Legacy", "SLUS-97199"),
        Candidate(4, "Crash Bandicoot", "Crash Bandicoot (Europe)"),
    )

    @Test
    fun `an exact title ranks at the exact tier`() {
        val hits = OrphanTitleRanker.rank("Crash Bandicoot", games)
        assertEquals(4L, hits.first().candidate.gameId)
        assertEquals(Tier.EXACT, hits.first().tier)
    }

    @Test
    fun `a roman-numeral query finds an arabic-numeral game`() {
        val hits = OrphanTitleRanker.rank("Final Fantasy VII", games)
        assertEquals(2L, hits.first().candidate.gameId)
    }

    @Test
    fun `agreement only after canonicalizing ranks at the canonical tier`() {
        // No ROM stem to match exactly on, so the numeral rule is what connects these two.
        val titleOnly = listOf(Candidate(8, "Final Fantasy 7", null))
        val hits = OrphanTitleRanker.rank("Final Fantasy VII", titleOnly)
        assertEquals(Tier.CANONICAL, hits.single().tier)
    }

    @Test
    fun `the article convention finds the naturally-titled game`() {
        val hits = OrphanTitleRanker.rank("Legend of Zelda, The - Ocarina of Time", games)
        assertEquals(1L, hits.first().candidate.gameId)
    }

    @Test
    fun `a serial-named rom is findable by its stem`() {
        val hits = OrphanTitleRanker.rank("SLUS-97199", games)
        assertEquals(3L, hits.first().candidate.gameId)
    }

    @Test
    fun `a partial query matches by whole words at the containment tier`() {
        val hits = OrphanTitleRanker.rank("Ocarina", games)
        assertEquals(1L, hits.first().candidate.gameId)
        assertEquals(Tier.CONTAINS, hits.first().tier)
    }

    @Test
    fun `containment is whole-word, not substring`() {
        val ball = listOf(Candidate(9, "Smart Ball", null))
        assertTrue(OrphanTitleRanker.rank("art", ball).isEmpty())
    }

    @Test
    fun `a query matching nothing returns empty`() {
        assertTrue(OrphanTitleRanker.rank("Halo", games).isEmpty())
    }

    @Test
    fun `a blank query returns empty — never the whole library`() {
        assertTrue(OrphanTitleRanker.rank("", games).isEmpty())
        assertTrue(OrphanTitleRanker.rank("   ", games).isEmpty())
    }

    @Test
    fun `better tiers sort first`() {
        val mixed = listOf(
            Candidate(10, "Crash Bandicoot 2 Cortex Strikes Back", null),  // contains
            Candidate(11, "Crash Bandicoot", null),                        // exact
        )
        val hits = OrphanTitleRanker.rank("Crash Bandicoot", mixed)
        assertEquals(listOf(11L, 10L), hits.map { it.candidate.gameId })
    }

    @Test
    fun `ranking is stable for equal tiers`() {
        val twins = listOf(Candidate(20, "Sonic Adventure", null), Candidate(21, "Sonic Adventure", null))
        assertEquals(listOf(20L, 21L), OrphanTitleRanker.rank("Sonic Adventure", twins).map { it.candidate.gameId })
    }
}
