package com.playfieldportal.feature.artwork.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Title 5-Rule Normalizer (C23 T6, Phase 20).
 *
 * The suite is split the way the risk is. The first half checks that the rules DO something —
 * trademarks, case, punctuation, editions, executable noise. The second half is the half that
 * matters: normalization must never merge two games that are genuinely different, because a false
 * positive writes a permanent wrong identity while a miss only costs a confirmation prompt.
 */
class StorefrontTitleNormalizerTest {

    private fun key(title: String) = StorefrontTitleNormalizer.normalize(title).comparisonKey

    // -- Rule 1: unicode, trademarks -------------------------------------------

    @Test
    fun `trademark signs are removed rather than decomposed into letters`() {
        val normalized = StorefrontTitleNormalizer.normalize("FINAL FANTASY VII™ REMAKE")

        // The bug this pins: NFKC maps the trademark sign to "tm", so a normalizer that ran
        // ArtworkNaming first would produce "final fantasy viitm remake" and match nothing.
        assertEquals("final fantasy vii remake", normalized.searchTitle)
        assertTrue("tm" !in normalized.comparisonKey)
    }

    @Test
    fun `registered and copyright signs go the same way`() {
        assertEquals(
            "sid meier's civilization vi",
            StorefrontTitleNormalizer.normalize("Sid Meier's Civilization® VI").searchTitle,
        )
        assertEquals(
            "tom clancy's rainbow six siege",
            StorefrontTitleNormalizer.normalize("Tom Clancy's Rainbow Six® Siege").searchTitle,
        )
    }

    @Test
    fun `curly apostrophes and straight ones normalize to one form`() {
        assertEquals(
            key("DEATH STRANDING DIRECTOR'S CUT"),
            key("DEATH STRANDING DIRECTOR’S CUT"),
        )
    }

    // -- Rule 2: case and whitespace -------------------------------------------

    @Test
    fun `case is folded and repeated whitespace collapsed`() {
        assertEquals(
            "final fantasy vii",
            StorefrontTitleNormalizer.normalize("  Final   Fantasy   VII  ").searchTitle,
        )
    }

    // -- Rule 3: punctuation ---------------------------------------------------

    @Test
    fun `a colon does not stop two spellings of one title from meeting`() {
        assertEquals(key("NieR:Automata"), key("NieR Automata"))
    }

    @Test
    fun `the search term keeps the punctuation a store search reads better`() {
        // Rule 3's own warning: comparison consistency, not aggressive rewriting. The colon and the
        // apostrophe survive into what Steam is actually asked for; only the KEY flattens them.
        val normalized = StorefrontTitleNormalizer.normalize("NieR:Automata")
        assertEquals("nier:automata", normalized.searchTitle)
        assertEquals("nier automata", normalized.comparisonKey)
    }

    // -- Rule 4: editions ------------------------------------------------------

    @Test
    fun `an edition suffix produces a SECOND candidate and never replaces the first`() {
        val normalized = StorefrontTitleNormalizer.normalize("Control Ultimate Edition")

        assertEquals("control ultimate edition", normalized.searchTitle)
        assertEquals("control", normalized.editionlessTitle)
        assertEquals(listOf("control ultimate edition", "control"), normalized.searchCandidates)
    }

    @Test
    fun `the witcher 3 generates both forms in the documented order`() {
        val normalized =
            StorefrontTitleNormalizer.normalize("The Witcher 3: Wild Hunt - Complete Edition")

        assertEquals(
            listOf("the witcher 3: wild hunt - complete edition", "the witcher 3: wild hunt"),
            normalized.searchCandidates,
        )
        // The article and the punctuation are gone from the KEY, which is what is compared.
        assertEquals("witcher 3 wild hunt complete edition", normalized.comparisonKey)
        assertEquals("witcher 3 wild hunt", normalized.editionlessComparisonKey)
    }

    @Test
    fun `a title with no edition suffix generates exactly one candidate`() {
        val normalized = StorefrontTitleNormalizer.normalize("DOOM")

        assertNull(normalized.edition)
        assertNull(normalized.editionlessTitle)
        assertEquals(listOf("doom"), normalized.searchCandidates)
    }

    @Test
    fun `a word that only looks like an edition inside the title is kept`() {
        // "Gold" is the title here, not packaging. Rule 4 only matches a trailing phrase.
        assertEquals("pokemon gold", StorefrontTitleNormalizer.normalize("Pokemon Gold").searchTitle)
        assertNull(StorefrontTitleNormalizer.normalize("Pokemon Gold").editionlessTitle)
    }

    // -- Rule 5: launcher and executable noise ---------------------------------

    @Test
    fun `executable and launcher noise is dropped`() {
        assertEquals(
            "borderlands 3",
            StorefrontTitleNormalizer.normalize("Borderlands 3 Launcher").searchTitle,
        )
        assertEquals(
            "elden ring",
            StorefrontTitleNormalizer.normalize("Elden Ring Win64 Shipping.exe").searchTitle,
        )
    }

    @Test
    fun `a technical-looking word outside the controlled list survives`() {
        // Rule 5 is a list, not a heuristic. "Core" and "Engine" are titles as often as not.
        assertEquals("deus ex machina engine", StorefrontTitleNormalizer.normalize("Deus Ex Machina Engine").searchTitle)
    }

    @Test
    fun `noise removal never empties a title`() {
        // A shortcut literally called Launcher.exe is a bad name, not a reason to search for "".
        assertEquals("launcher", StorefrontTitleNormalizer.normalize("Launcher.exe").searchTitle)
    }

    // -- The half that matters: what must NOT merge ----------------------------

    @Test
    fun `DOOM and DOOM II stay different games`() {
        assertNotEquals(key("DOOM"), key("DOOM II"))
        // And the numeral is normalized rather than dropped, so DOOM II still meets DOOM 2.
        assertEquals(key("DOOM II"), key("DOOM 2"))
    }

    @Test
    fun `the final fantasy seventh-instalment family stays three games`() {
        val seven = key("Final Fantasy VII")
        val remake = key("Final Fantasy VII Remake")
        val rebirth = key("Final Fantasy VII Rebirth")

        assertNotEquals(seven, remake)
        assertNotEquals(seven, rebirth)
        assertNotEquals(remake, rebirth)
    }

    @Test
    fun `remake and intergrade are not edition suffixes`() {
        val intergrade = StorefrontTitleNormalizer.normalize("FINAL FANTASY VII REMAKE INTERGRADE™")

        assertEquals("final fantasy vii remake intergrade", intergrade.searchTitle)
        // Nothing was stripped, so there is one candidate — the broader "final fantasy vii remake"
        // is a DIFFERENT PRODUCT and must never be generated as an alias for this one.
        assertNull(intergrade.editionlessTitle)
    }

    @Test
    fun `resident evil 4 and the 2005 original are told apart by the year, not merged`() {
        val modern = StorefrontTitleNormalizer.normalize("Resident Evil 4")
        val original = StorefrontTitleNormalizer.normalize("Resident Evil 4 (2005)")

        // The names really are identical once the tag is gone — which is exactly why the year is
        // lifted out instead of discarded. These two normalized titles are NOT equal.
        assertNull(modern.yearHint)
        assertEquals(2005, original.yearHint)
        assertNotEquals(modern, original)
    }

    @Test
    fun `an edition variant broadens discovery without claiming to be the base game`() {
        val gold = StorefrontTitleNormalizer.normalize("Resident Evil 4 Gold Edition")

        // The PRIMARY key is still its own, distinct from the base game's...
        assertNotEquals(key("Resident Evil 4"), gold.comparisonKey)
        // ...and only the secondary, explicitly weaker key reaches the base game.
        assertEquals(key("Resident Evil 4"), gold.editionlessComparisonKey)
    }

    @Test
    fun `a director's cut is a different product, not an edition`() {
        val cut = StorefrontTitleNormalizer.normalize("DEATH STRANDING DIRECTOR'S CUT")

        assertNull(cut.editionlessTitle)
        assertNotEquals(key("DEATH STRANDING"), cut.comparisonKey)
    }

    @Test
    fun `blank in, blank out`() {
        val blank = StorefrontTitleNormalizer.normalize("   ")

        assertEquals("", blank.searchTitle)
        assertTrue(blank.searchCandidates.isEmpty())
    }
}
