package com.playfieldportal.core.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The free-flow grid: cells of their own widths packed left to right into rows, as the game and
 * app pickers lay out their tiles. LEFT/RIGHT step within a row and never wrap; UP/DOWN land on
 * the cell in the next row whose centre is nearest the cursor's, so rows of different lengths and
 * widths still read as a grid.
 */
class FlowGridTest {

    private fun cells(vararg widths: Float) = widths.mapIndexed { i, w -> FlowCell("c$i", w) }

    // ── Packing ──────────────────────────────────────────────────────────────

    @Test
    fun `rows fill greedily and spill to the next`() {
        assertEquals(listOf(0..2, 3..4), packFlowRows(listOf(50f, 50f, 50f, 50f, 50f), available = 170f, spacing = 10f))
        // Exactly fitting is still a fit.
        assertEquals(listOf(0..1), packFlowRows(listOf(80f, 80f), available = 170f, spacing = 10f))
    }

    @Test
    fun `a cell wider than the space gets a row of its own, and so does every cell before measuring`() {
        assertEquals(listOf(0..0, 1..1), packFlowRows(listOf(300f, 50f), available = 100f, spacing = 8f))
        assertEquals(listOf(0..0, 1..1, 2..2), packFlowRows(listOf(50f, 50f, 50f), available = 0f, spacing = 8f))
        assertEquals(emptyList(), packFlowRows(emptyList(), available = 500f, spacing = 8f))
    }

    @Test
    fun `packing keeps each cell's key and width, row by row`() {
        val grid = FlowGrid.pack(cells(50f, 50f, 50f), available = 110f, spacing = 10f)
        assertEquals(listOf(listOf("c0", "c1"), listOf("c2")), grid.rows.map { row -> row.map { it.key } })
        assertEquals(listOf("c0", "c1", "c2"), grid.keys)
    }

    @Test
    fun `equal cells packed to a whole number of columns are a plain fixed-column grid`() {
        val grid = FlowGrid.pack(List(10) { FlowCell("a$it", 1f) }, available = 7f, spacing = 0f)
        assertEquals(listOf(7, 3), grid.rows.map { it.size })
    }

    // ── Movement ─────────────────────────────────────────────────────────────

    // Centres: row 0 at 61.5, 163, 235; row 1 at 61.5, 163.
    private val mixed = FlowGrid(
        rows = listOf(
            listOf(FlowCell("snes1", 123f), FlowCell("psp1", 64f), FlowCell("psp2", 64f)),
            listOf(FlowCell("snes2", 123f), FlowCell("psp3", 64f)),
        ),
        spacing = 8f,
    )

    @Test
    fun `up and down land on the cell nearest the cursor's centre`() {
        assertEquals("snes2", mixed.move("snes1", NavigationDirection.DOWN))
        assertEquals("psp3", mixed.move("psp1", NavigationDirection.DOWN))
        assertEquals("psp3", mixed.move("psp2", NavigationDirection.DOWN))   // past the shorter row's end
        assertEquals("psp1", mixed.move("psp3", NavigationDirection.UP))
    }

    @Test
    fun `movement stops at every edge - no wrap`() {
        assertNull(mixed.move("psp1", NavigationDirection.UP))
        assertNull(mixed.move("psp3", NavigationDirection.DOWN))
        assertNull(mixed.move("snes1", NavigationDirection.LEFT))
        assertNull(mixed.move("psp2", NavigationDirection.RIGHT))
        assertNull(mixed.move("snes2", NavigationDirection.LEFT))
    }

    @Test
    fun `left and right stay inside the row`() {
        assertEquals("psp1", mixed.move("snes1", NavigationDirection.RIGHT))
        assertEquals("snes1", mixed.move("psp1", NavigationDirection.LEFT))
        assertNull(mixed.move("psp3", NavigationDirection.RIGHT))
    }

    @Test
    fun `a key that is not in the grid goes nowhere`() {
        assertNull(mixed.move("gone", NavigationDirection.DOWN))
    }

    @Test
    fun `equal ties go to the left cell`() {
        val grid = FlowGrid(listOf(listOf(FlowCell("a", 10f), FlowCell("b", 10f)), listOf(FlowCell("wide", 30f))), spacing = 10f)
        // "wide" centres at 15, exactly between a (5) and b (25).
        assertEquals("a", grid.move("wide", NavigationDirection.UP))
    }
}
