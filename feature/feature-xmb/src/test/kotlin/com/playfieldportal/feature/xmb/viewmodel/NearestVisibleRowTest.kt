package com.playfieldportal.feature.xmb.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the touch → pad re-anchor rule for both lists that use it (the fullscreen music browser and
 * the Add-Tracks picker). Both hand [nearestToViewportCentre] the visible window's own **row
 * centres** and the centre of the viewport, so these cases are the whole rule: whichever row the
 * middle of what is on screen sits inside.
 */
class NearestVisibleRowTest {

    @Test
    fun `picks the row the middle of the screen sits inside`() {
        // 80px rows (centres 40, 120, 200, 280) down a 400px viewport, so the centre is 190.
        val visible = mapOf("a" to 40f, "b" to 120f, "c" to 200f, "d" to 280f)
        assertEquals("c", nearestToViewportCentre(190f, visible))
    }

    @Test
    fun `a row owns its whole span, not just its top edge`() {
        // The centre of the 400px viewport sits at 390, in the LOWER half of the ninth row
        // (320..400, centre 360). Comparing row *tops* against the centre would pick the row below
        // instead — an off-by-one row on half of all scroll positions.
        val visible = mapOf("eighth" to 280f, "ninth" to 360f, "tenth" to 440f)
        assertEquals("ninth", nearestToViewportCentre(390f, visible))
    }

    @Test
    fun `a row scrolled half off the top can still win`() {
        // Compose reports a partially visible top row with a negative offset, so its centre lands
        // just below the start of the viewport. A list stopped with that row across the middle of
        // the window must re-anchor to it, not to the first row fully inside.
        val visible = mapOf("halfOff" to 0f, "next" to 80f, "next2" to 160f)
        assertEquals("halfOff", nearestToViewportCentre(20f, visible))
    }

    @Test
    fun `the choice follows the middle of the window, not the ends`() {
        val visible = mapOf("top" to 20f, "middle" to 190f, "bottom" to 380f)
        assertEquals("middle", nearestToViewportCentre(200f, visible))
    }

    @Test
    fun `nothing visible leaves the cursor alone`() {
        assertNull(nearestToViewportCentre<Int>(200f, emptyMap()))
    }

    @Test
    fun `the picker's integer row indexes work the same way`() {
        // Index 0 is that picker's Confirm row; the centres are its own list geometry.
        val visible = mapOf(0 to 40f, 1 to 120f, 2 to 200f, 3 to 280f)
        assertEquals(2, nearestToViewportCentre(200f, visible))
    }
}
