package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.GamepadAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cursor movement over the theme icon grid: the tiles are one flattened list, laid out in
 * [COLUMNS] columns, with each section starting on a fresh row. Nothing wraps.
 */
class ThemeIconGridNavTest {

    private val columns = 6

    // "Theme icons" has 14 tiles (rows of 6, 6, 2: flat 0..13), "More from this PSP theme" has 4
    // (one row: flat 14..17).
    private val sizes = listOf(14, 4)

    private fun move(index: Int, action: GamepadAction, sectionSizes: List<Int> = sizes) =
        ThemeIconGridNav.move(sectionSizes, columns, index, action)

    @Test
    fun `the shared constant is the six columns of the approved mockup`() {
        assertEquals(6, THEME_ICON_GRID_COLUMNS)
    }

    @Test
    fun `right steps one tile and clamps at the last tile`() {
        assertEquals(1, move(0, GamepadAction.NAVIGATE_RIGHT))
        assertEquals(17, move(17, GamepadAction.NAVIGATE_RIGHT))
    }

    @Test
    fun `right crosses from one section into the next`() {
        assertEquals(14, move(13, GamepadAction.NAVIGATE_RIGHT))
    }

    @Test
    fun `left steps back and clamps at the first tile`() {
        assertEquals(4, move(5, GamepadAction.NAVIGATE_LEFT))
        assertEquals(0, move(0, GamepadAction.NAVIGATE_LEFT))
    }

    @Test
    fun `down from row 1 col 3 lands on row 2 col 3`() {
        // Row 1 starts at 0, row 2 at 6: column 3 is flat 3, then flat 9.
        assertEquals(9, move(3, GamepadAction.NAVIGATE_DOWN))
    }

    @Test
    fun `down into a shorter row clamps to its last tile`() {
        // Row 3 of the first section has only flat 12 and 13; column 5 of row 2 (flat 11) lands on 13.
        assertEquals(13, move(11, GamepadAction.NAVIGATE_DOWN))
    }

    @Test
    fun `down from the last row of the first section lands on the first row of the next`() {
        assertEquals(14, move(12, GamepadAction.NAVIGATE_DOWN))
        // Column 1 of the short row maps to column 1 of the next section's row.
        assertEquals(15, move(13, GamepadAction.NAVIGATE_DOWN))
    }

    @Test
    fun `down on the last row stays put`() {
        assertEquals(16, move(16, GamepadAction.NAVIGATE_DOWN))
    }

    @Test
    fun `up goes to the same column in the row above and into the previous section`() {
        assertEquals(3, move(9, GamepadAction.NAVIGATE_UP))
        // From the next section's first row, column 3 is above in the short row: clamps to its last tile.
        assertEquals(13, move(17, GamepadAction.NAVIGATE_UP))
    }

    @Test
    fun `up from the first row stays put`() {
        assertEquals(3, move(3, GamepadAction.NAVIGATE_UP))
        assertEquals(0, move(0, GamepadAction.NAVIGATE_UP))
    }

    @Test
    fun `other actions and an empty grid change nothing`() {
        assertEquals(4, move(4, GamepadAction.SELECT))
        assertEquals(0, move(0, GamepadAction.NAVIGATE_DOWN, sectionSizes = emptyList()))
    }

    @Test
    fun `an out of range cursor is pulled back onto the grid`() {
        // Clamped onto the grid first (last tile 17, first tile 0), then moved.
        assertEquals(16, move(40, GamepadAction.NAVIGATE_LEFT))
        assertEquals(1, move(-3, GamepadAction.NAVIGATE_RIGHT))
    }

    @Test
    fun `mime follows the stored extension`() {
        assertEquals("image/png", themeIconMime(java.io.File("a.png")))
        assertEquals("image/gif", themeIconMime(java.io.File("a.GIF")))
        assertEquals("image/jpeg", themeIconMime(java.io.File("a.jpg")))
    }
}
