package com.playfieldportal.feature.xmb.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the touch → pad re-anchor rule for both lists that use it (the fullscreen music browser and
 * the Add-Tracks picker). Both hand [nearestToViewportCentre] the visible window's own offsets, so
 * these cases are the whole rule: whichever row sits closest to the middle of what is on screen.
 */
class NearestVisibleRowTest {

    @Test
    fun `picks the row whose top is closest to the viewport centre`() {
        // 80px rows down a 400px viewport: the centre sits inside the third row.
        val visible = mapOf("a" to 0f, "b" to 80f, "c" to 160f, "d" to 240f)
        assertEquals("c", nearestToViewportCentre(190f, visible))
    }

    @Test
    fun `a row scrolled half off the top can still win`() {
        // Compose reports a partially visible top row as a NEGATIVE offset. A list stopped with that
        // row across the middle of the window must re-anchor to it, not to the first row that is
        // fully inside the viewport.
        val visible = mapOf("halfOff" to -20f, "next" to 90f, "next2" to 200f)
        assertEquals("halfOff", nearestToViewportCentre(20f, visible))
    }

    @Test
    fun `the choice follows the middle of the window, not the ends`() {
        val visible = mapOf("top" to 0f, "middle" to 190f, "bottom" to 380f)
        assertEquals("middle", nearestToViewportCentre(200f, visible))
    }

    @Test
    fun `nothing visible leaves the cursor alone`() {
        assertNull(nearestToViewportCentre<Int>(200f, emptyMap()))
    }

    @Test
    fun `the picker's integer row indexes work the same way`() {
        // Index 0 is that picker's Confirm row; the offsets are its own list geometry.
        val visible = mapOf(0 to 0f, 1 to 90f, 2 to 180f, 3 to 270f)
        assertEquals(2, nearestToViewportCentre(200f, visible))
    }
}
