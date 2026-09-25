package com.playfieldportal.feature.artwork.match

import org.junit.Assert.assertEquals
import org.junit.Test

/** C22 task T2 — the canonical match form's two rules, and what they deliberately do not touch. */
class TitleCanonTest {

    // ── Article rule ──────────────────────────────────────────────────────────

    @Test
    fun `leading the is dropped`() {
        assertEquals("legend of zelda", TitleCanon.of("the legend of zelda"))
    }

    @Test
    fun `trailing the is dropped — the No-Intro convention`() {
        assertEquals("legend of zelda", TitleCanon.of("legend of zelda the"))
    }

    @Test
    fun `both spellings of one game reach the same canonical form`() {
        assertEquals(
            TitleCanon.of("the legend of zelda ocarina of time"),
            TitleCanon.of("legend of zelda the ocarina of time"),
        )
    }

    @Test
    fun `the is dropped at a subtitle boundary too — the convention's real shape`() {
        // "Legend of Zelda, The - Ocarina of Time" simplifies to this. The article sits at the end
        // of the MAIN title, not the string, and the comma is already gone by the time we see it.
        assertEquals(
            TitleCanon.of("the legend of zelda ocarina of time"),
            TitleCanon.of("legend of zelda the ocarina of time"),
        )
        assertEquals("legend of zelda ocarina of time", TitleCanon.of("legend of zelda the ocarina of time"))
    }

    @Test
    fun `dropping the mid-title is symmetric, so both spellings still meet`() {
        assertEquals(
            TitleCanon.of("jak and daxter the precursor legacy"),
            TitleCanon.of("jak and daxter precursor legacy"),
        )
    }

    @Test
    fun `a word merely starting with the is not mangled`() {
        assertEquals("theme park", TitleCanon.of("theme park"))
    }

    @Test
    fun `no other article is stripped`() {
        assertEquals("a boy and his blob", TitleCanon.of("a boy and his blob"))
    }

    @Test
    fun `a title that is only an article canonicalizes to nothing`() {
        assertEquals("", TitleCanon.of("the"))
    }

    // ── Numeral rule ──────────────────────────────────────────────────────────

    @Test
    fun `multi-character roman numerals become arabic`() {
        assertEquals("final fantasy 7", TitleCanon.of("final fantasy vii"))
        assertEquals("final fantasy 13", TitleCanon.of("final fantasy xiii"))
        assertEquals("final fantasy 9", TitleCanon.of("final fantasy ix"))
        assertEquals("final fantasy 4", TitleCanon.of("final fantasy iv"))
    }

    @Test
    fun `bare i v and x convert too`() {
        assertEquals("rocky 5", TitleCanon.of("rocky v"))
        assertEquals("final fantasy 10", TitleCanon.of("final fantasy x"))
        assertEquals("1 am alive", TitleCanon.of("i am alive"))
    }

    @Test
    fun `mega man x collides with mega man 10 — the accepted cost, pinned as intended`() {
        // Pass 5's uniqueness valve turns this into Ambiguous rather than a wrong link; see
        // ArtworkImportMatcherTest. Recorded here so the collision is never "fixed" by accident.
        assertEquals(TitleCanon.of("mega man 10"), TitleCanon.of("mega man x"))
    }

    @Test
    fun `an ordinary word containing numeral letters is not a numeral`() {
        assertEquals("mix", TitleCanon.of("mix"))
        assertEquals("civilization", TitleCanon.of("civilization"))
        assertEquals("vice city", TitleCanon.of("vice city"))
    }

    @Test
    fun `arabic numerals are left as they are`() {
        assertEquals("final fantasy 7", TitleCanon.of("final fantasy 7"))
    }

    // ── Shape ─────────────────────────────────────────────────────────────────

    @Test
    fun `both rules apply together`() {
        assertEquals("legend of zelda 2", TitleCanon.of("the legend of zelda ii"))
        assertEquals("legend of zelda 2", TitleCanon.of("legend of zelda the ii"))
    }

    @Test
    fun `is idempotent`() {
        val once = TitleCanon.of("the legend of zelda ii")
        assertEquals(once, TitleCanon.of(once))
    }

    @Test
    fun `blank in, blank out`() {
        assertEquals("", TitleCanon.of(""))
        assertEquals("", TitleCanon.of("   "))
    }
}
