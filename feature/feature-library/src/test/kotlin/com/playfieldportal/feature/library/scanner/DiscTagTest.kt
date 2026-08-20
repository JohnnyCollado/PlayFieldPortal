package com.playfieldportal.feature.library.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure disc-tag parser: every tag form real dumps use parses to the right number and a title
 * with only the tag removed, while non-tag titles (including ones containing the word "disc") are
 * left alone. See docs/plans/multi-disc-games-plan.md step 1.
 */
class DiscTagTest {

    @Test
    fun `parenthesized Disc N parses`() {
        val tag = parseDiscTag("Final Fantasy VII (Disc 1) (USA)")
        assertEquals(1, tag!!.discNumber)
        assertNull(tag.discTotal)
        assertEquals("Final Fantasy VII (USA)", tag.strippedTitle)
    }

    @Test
    fun `Disc N of M parses with a total`() {
        val tag = parseDiscTag("Metal Gear Solid (Disc 1 of 3) (Europe)")
        assertEquals(1, tag!!.discNumber)
        assertEquals(3, tag.discTotal)
        assertEquals("Metal Gear Solid (Europe)", tag.strippedTitle)
    }

    @Test
    fun `Disk spelling parses`() {
        val tag = parseDiscTag("Resident Evil 2 (Disk 2)")
        assertEquals(2, tag!!.discNumber)
        assertNull(tag.discTotal)
        assertEquals("Resident Evil 2", tag.strippedTitle)
    }

    @Test
    fun `CD N with and without space parses`() {
        assertEquals(1, parseDiscTag("Panzer Dragoon Saga (CD1)")!!.discNumber)
        assertEquals(2, parseDiscTag("Panzer Dragoon Saga (CD 2)")!!.discNumber)
    }

    @Test
    fun `bracketed Disc N parses`() {
        val tag = parseDiscTag("Silent Hill [Disc 2]")
        assertEquals(2, tag!!.discNumber)
        assertEquals("Silent Hill", tag.strippedTitle)
    }

    @Test
    fun `trailing dash Disc N parses`() {
        val tag = parseDiscTag("Final Fantasy VIII - Disc 2")
        assertEquals(2, tag!!.discNumber)
        assertNull(tag.discTotal)
        assertEquals("Final Fantasy VIII", tag.strippedTitle)
    }

    @Test
    fun `trailing dash Disc N of M parses`() {
        val tag = parseDiscTag("Lunar: Silver Star Story - Disc 3 of 4")
        assertEquals(3, tag!!.discNumber)
        assertEquals(4, tag.discTotal)
        assertEquals("Lunar: Silver Star Story", tag.strippedTitle)
    }

    @Test
    fun `tag position in the group ordering does not matter`() {
        assertEquals(
            parseDiscTag("Final Fantasy VII (Disc 1) (USA)"),
            parseDiscTag("Final Fantasy VII (USA) (Disc 1)"),
        )
    }

    @Test
    fun `a title containing the word disc is not a tag`() {
        assertNull(parseDiscTag("Disc Jam"))
        assertNull(parseDiscTag("Disc World (USA)"))
    }

    @Test
    fun `a title with no disc tag returns null`() {
        assertNull(parseDiscTag("Chrono Trigger (USA)"))
        assertNull(parseDiscTag("Super Mario World"))
    }

    @Test
    fun `a tag without a number is not a tag`() {
        assertNull(parseDiscTag("Game (Disc) (USA)"))
    }
}
