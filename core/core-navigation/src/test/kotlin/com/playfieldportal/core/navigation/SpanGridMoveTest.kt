package com.playfieldportal.core.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Cursor movement over a grid whose cells may span several columns (Virtual Keyboard plan 6.2).
 * The keyboard: four rows of ten single keys, then Shift(1) · layer(2) · Space(4) · Backspace(1) ·
 * Done(2).
 */
class SpanGridMoveTest {

    private val uniform = List(10) { 1 }
    private val keyboard = listOf(uniform, uniform, uniform, uniform, listOf(1, 2, 4, 1, 2))

    private fun at(row: Int, cell: Int, anchor: Int? = null) = SpanGridCursor(row, cell, anchor)

    private fun move(from: SpanGridCursor, direction: NavigationDirection) = spanGridMove(keyboard, from, direction)

    @Test
    fun `left and right step one cell in a row and stop at either end`() {
        assertEquals(1, move(at(1, 0), NavigationDirection.RIGHT)?.cell)
        assertEquals(3, move(at(1, 4), NavigationDirection.LEFT)?.cell)
        assertNull(move(at(1, 0), NavigationDirection.LEFT))
        assertNull(move(at(1, 9), NavigationDirection.RIGHT))
    }

    @Test
    fun `up and down between uniform rows keep the column`() {
        val down = move(at(1, 6), NavigationDirection.DOWN)
        assertEquals(2, down?.row)
        assertEquals(6, down?.cell)
        assertEquals(6, move(at(2, 6), NavigationDirection.UP)?.cell)
    }

    @Test
    fun `down into the spanning row lands on the cell covering the column`() {
        val expected = mapOf(0 to 0, 1 to 1, 2 to 1, 3 to 2, 4 to 2, 5 to 2, 6 to 2, 7 to 3, 8 to 4, 9 to 4)
        expected.forEach { (column, cell) ->
            val result = move(at(3, column), NavigationDirection.DOWN)
            assertEquals(4, result?.row, "column $column")
            assertEquals(cell, result?.cell, "column $column")
        }
    }

    @Test
    fun `up from a spanning cell entered vertically returns to the column it came from`() {
        // b is row 3, column 4: down onto Space, then up gives b again, not v.
        val onSpace = move(at(3, 4), NavigationDirection.DOWN)!!
        assertEquals(2, onSpace.cell)
        val back = move(onSpace, NavigationDirection.UP)
        assertEquals(3, back?.row)
        assertEquals(4, back?.cell)
    }

    @Test
    fun `up from a spanning cell reached horizontally uses its first column`() {
        val onSpace = move(at(4, 1), NavigationDirection.RIGHT)!!
        assertEquals(2, onSpace.cell)
        assertEquals(3, move(onSpace, NavigationDirection.UP)?.cell)

        val onDone = move(at(4, 3), NavigationDirection.RIGHT)!!
        assertEquals(8, move(onDone, NavigationDirection.UP)?.cell)
    }

    @Test
    fun `right along the spanning row visits each key in order`() {
        val visited = generateSequence(at(4, 0)) { move(it, NavigationDirection.RIGHT) }.map { it.cell }.toList()
        assertEquals(listOf(0, 1, 2, 3, 4), visited)
    }

    @Test
    fun `up on the first row and down on the last return null`() {
        assertNull(move(at(0, 3), NavigationDirection.UP))
        assertNull(move(at(4, 2), NavigationDirection.DOWN))
    }

    @Test
    fun `an empty grid or an out-of-range position returns null`() {
        assertNull(spanGridMove(emptyList(), at(0, 0), NavigationDirection.DOWN))
        assertNull(move(at(5, 0), NavigationDirection.UP))
        assertNull(move(at(4, 5), NavigationDirection.LEFT))
        assertNull(move(at(-1, 0), NavigationDirection.DOWN))
    }

    // ── Wrapping (the virtual keyboard cycles through its keys) ─────────────

    private fun wrap(from: SpanGridCursor, direction: NavigationDirection) = spanGridMove(keyboard, from, direction, wrap = true)

    @Test
    fun `wrapping left and right cycle within the row`() {
        assertEquals(9, wrap(at(1, 0), NavigationDirection.LEFT)?.cell)
        assertEquals(0, wrap(at(1, 9), NavigationDirection.RIGHT)?.cell)
        // The bottom row's last cell (Done) wraps to its first (Shift).
        assertEquals(0, wrap(at(4, 4), NavigationDirection.RIGHT)?.cell)
    }

    @Test
    fun `wrapping up and down cycle through the rows, keeping the column`() {
        val upFromTop = wrap(at(0, 6), NavigationDirection.UP)
        // Column 6 on the bottom row is Space (columns 3-6).
        assertEquals(4, upFromTop?.row)
        assertEquals(2, upFromTop?.cell)
        assertEquals(SpanGridCursor(0, 6, 6), wrap(at(4, 2, anchor = 6), NavigationDirection.DOWN))
    }

    @Test
    fun `without wrap the edges still stop`() {
        assertNull(move(at(1, 0), NavigationDirection.LEFT))
        assertNull(move(at(0, 0), NavigationDirection.UP))
    }
}
