package com.playfieldportal.core.domain.model.emulatorkb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformExtensionPlannerTest {

    private val seed = listOf("cue", "bin", "iso", "zip")
    private val kb = listOf("cue", "bin", "chd")

    private fun plan(
        lastApplied: List<String>? = null,
        kbList: List<String> = kb,
        platform: List<String> = seed,
        cards: List<List<String>> = emptyList(),
    ) = PlatformExtensionPlanner.plan(seed, lastApplied, kbList, platform, cards)

    @Test
    fun untouchedPlatformAndCardGainChd() {
        val p = plan(platform = seed, cards = listOf(seed))
        assertEquals(listOf("cue", "bin", "iso", "zip", "chd"), p.platform)
        assertEquals(listOf(listOf("cue", "bin", "iso", "zip", "chd")), p.cards)
        assertEquals(listOf("chd"), p.added)
        assertEquals(listOf("cue", "bin", "iso", "zip", "chd"), p.lastApplied)
    }

    @Test
    fun cardWhereUserRemovedZipGainsChdAndZipStaysRemoved() {
        val p = plan(cards = listOf(listOf("cue", "bin", "iso")))
        assertEquals(listOf(listOf("cue", "bin", "iso", "chd")), p.cards)
    }

    @Test
    fun aRemovalStaysRemovedEvenWhenTheKbStillListsIt() {
        // The user dropped zip; the KB still lists zip, and adds chd. Only chd is new.
        val p = plan(kbList = listOf("zip", "chd"), cards = listOf(listOf("cue", "bin", "iso")))
        assertEquals(listOf(listOf("cue", "bin", "iso", "chd")), p.cards)
        assertEquals(listOf("chd"), p.added)
    }

    @Test
    fun cardWhereUserAdded7zGainsChdAndKeeps7z() {
        val p = plan(cards = listOf(seed + "7z"))
        assertEquals(listOf(seed + "7z" + "chd"), p.cards)
        assertEquals(listOf("cue", "bin", "iso", "zip", "chd"), p.platform)
    }

    @Test
    fun anEditedCardOnlyGainsWhatIsNewSinceTheLastApply() {
        // chd arrived in the last KB update; pbp arrives now. The user has since removed chd and iso.
        val prev = listOf("cue", "bin", "iso", "zip", "chd")
        val p = plan(lastApplied = prev, kbList = prev + "pbp", platform = prev, cards = listOf(listOf("cue", "bin", "zip")))
        assertEquals(listOf(listOf("cue", "bin", "zip", "pbp")), p.cards)
    }

    @Test
    fun kbListMissingCurrentTokenRemovesNothing() {
        val p = plan(kbList = listOf("chd"), cards = listOf(seed))
        assertEquals(listOf("cue", "bin", "iso", "zip", "chd"), p.cards.single())
        assertTrue(p.platform!!.containsAll(seed))
    }

    @Test
    fun rerunAfterPartialApplyIsIdempotent() {
        val applied = listOf("cue", "bin", "iso", "zip", "chd")
        // Platform written, card write lost: the card still equals lastApplied-less seed, so it catches up.
        val p = plan(lastApplied = applied, platform = applied, cards = listOf(seed))
        assertNull(p.platform)
        assertEquals(listOf(applied), p.cards)
        // Fully applied: nothing to do.
        val again = plan(lastApplied = applied, platform = applied, cards = listOf(applied))
        assertNull(again.platform)
        assertEquals(listOf<List<String>?>(null), again.cards)
        assertTrue(again.added.isEmpty())
    }

    @Test
    fun cardEqualToSeedAfterRestoreIsUntouched() {
        val prev = listOf("cue", "bin", "iso", "zip", "chd")
        val p = plan(lastApplied = prev, kbList = prev + "pbp", cards = listOf(seed.reversed()))
        assertEquals(seed.reversed() + listOf("chd", "pbp"), p.cards.single())
    }

    @Test
    fun cardEqualToLastAppliedGainsNewTokens() {
        val prev = listOf("cue", "bin", "chd")
        val p = plan(lastApplied = prev, kbList = prev + "pbp", cards = listOf(prev))
        assertEquals(listOf("cue", "bin", "chd", "pbp"), p.cards.single())
    }

    @Test
    fun comparisonIsCaseInsensitiveAndOrderFree() {
        val p = plan(platform = listOf("ZIP", "ISO", "Bin", "cue"), cards = listOf(listOf("ZIP", "ISO", "Bin", "cue")))
        assertEquals(listOf("zip", "iso", "bin", "cue", "chd"), p.platform)
        assertEquals(listOf("chd"), p.added)
    }

    @Test
    fun customizedPlatformKeepsItsRemovalsAndGainsOnlyNewTokens() {
        val p = plan(platform = listOf("cue"), cards = listOf(listOf("cue")))
        assertEquals(listOf("cue", "chd"), p.platform)
        assertEquals(listOf(listOf("cue", "chd")), p.cards)
        assertEquals(listOf("cue", "bin", "iso", "zip", "chd"), p.lastApplied)
    }

    @Test
    fun anEditedRowThatAlreadyHasTheNewTokenIsNotRewritten() {
        val p = plan(cards = listOf(listOf("cue", "chd")))
        assertEquals(listOf<List<String>?>(null), p.cards)
    }

    @Test
    fun emptyKbListChangesNothing() {
        val p = plan(kbList = emptyList(), cards = listOf(seed))
        assertNull(p.platform)
        assertEquals(listOf<List<String>?>(null), p.cards)
        assertTrue(p.added.isEmpty())
    }
}
