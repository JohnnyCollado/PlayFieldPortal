package com.playfieldportal.feature.xmb.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the rule that makes a held direction read the same both ways: a one-row step down is seated
 * at the bottom edge (so the list travels one row), everything else keeps `scrollToItem`'s
 * top-align. Shared by the fullscreen music browser and the Add-Tracks picker.
 */
class ListRowRevealTest {

    @Test
    fun `a one-row step down scrolls back all but the row's own height`() {
        // The row is composed top-aligned, then the list scrolls back the rest of the viewport so
        // its bottom meets the bottom edge — which is exactly the row's height of travel.
        assertEquals(344, scrollBackForReveal(target = 9, lastVisibleIndex = 8, viewportLength = 400, rowHeight = 56))
    }

    @Test
    fun `a step up past the first visible row is left top-aligned`() {
        // Bringing a row to the top IS the minimal move when the cursor goes up: the row above the
        // window becomes the new top row, one row of travel. Scrolling back would push it off.
        assertEquals(0, scrollBackForReveal(target = 3, lastVisibleIndex = 8, viewportLength = 400, rowHeight = 56))
    }

    @Test
    fun `a jump of more than one row stays a jump`() {
        // A sort cycling back to the top, or a query that refilters the list, is not a cursor step
        // and must not be seated at the bottom edge.
        assertEquals(0, scrollBackForReveal(target = 40, lastVisibleIndex = 8, viewportLength = 400, rowHeight = 56))
    }

    @Test
    fun `a list that has not laid out yet is left alone`() {
        assertEquals(0, scrollBackForReveal(target = 0, lastVisibleIndex = null, viewportLength = 400, rowHeight = 56))
    }

    @Test
    fun `a row taller than the window cannot be seated flush`() {
        // Degenerate but reachable with a short window: leave it top-aligned, fully visible from
        // the top down, rather than scrolling it back off the top.
        assertEquals(0, scrollBackForReveal(target = 9, lastVisibleIndex = 8, viewportLength = 40, rowHeight = 120))
    }
}
