package com.playfieldportal.core.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A context laid out as a [FlowGrid], and a nested (non-modal) level pushed over a list: the
 * pickers' shape — a shelf list, and one shelf's tiles a level below it.
 */
class NavigationEngineFlowGridTest {

    private fun node(key: String, onSelect: (() -> Unit)? = null) = NavigationNode(key = key, onSelect = onSelect)

    private val grid = FlowGrid(
        rows = listOf(
            listOf(FlowCell("t0", 10f), FlowCell("t1", 10f), FlowCell("t2", 10f)),
            listOf(FlowCell("t3", 10f)),
        ),
    )

    private fun dir(d: NavigationDirection) = NavigationCommand.Direction(d)

    @Test
    fun `a flow grid context moves by the grid, not by the list`() {
        val engine = NavigationEngine()
        engine.replaceFlowGrid(grid, grid.keys.map { node(it) })
        engine.markReady()
        assertEquals("t0", engine.focusedKey)
        assertEquals("t1", engine.dispatch(dir(NavigationDirection.RIGHT)))
        assertEquals("t3", engine.dispatch(dir(NavigationDirection.DOWN)))
        // An edge keeps the cursor where it is.
        assertEquals("t3", engine.dispatch(dir(NavigationDirection.DOWN)))
        assertEquals("t3", engine.focusedKey)
    }

    @Test
    fun `readiness still gates a flow grid`() {
        val engine = NavigationEngine()
        engine.replaceFlowGrid(grid, grid.keys.map { node(it) })
        assertNull(engine.dispatch(dir(NavigationDirection.RIGHT)))
        assertEquals("t0", engine.focusedKey)
    }

    @Test
    fun `a grid whose focused cell is gone recovers to the cell that takes its place`() {
        val engine = NavigationEngine()
        engine.replaceFlowGrid(grid, grid.keys.map { node(it) })
        engine.setFocused("t2")
        val smaller = FlowGrid(listOf(listOf(FlowCell("t0", 10f), FlowCell("t1", 10f), FlowCell("t3", 10f))))
        engine.replaceFlowGrid(smaller, smaller.keys.map { node(it) })
        assertEquals("t3", engine.focusedKey)
    }

    @Test
    fun `a nested level is not a modal, and popping it gives the list back its focus`() {
        val engine = NavigationEngine("shelves")
        engine.replaceNodes(listOf(node("s0"), node("s1")))
        engine.markReady()
        engine.dispatch(dir(NavigationDirection.DOWN))
        engine.pushContext("grid")
        engine.replaceFlowGrid(grid, grid.keys.map { node(it) })
        assertEquals("grid", engine.activeContextId)
        assertFalse(engine.isModalActive)
        assertEquals("t1", engine.dispatch(dir(NavigationDirection.RIGHT)))
        assertEquals("s1", engine.popContext())
        assertEquals("shelves", engine.activeContextId)
    }

    @Test
    fun `a modal over a nested level is still a modal`() {
        val engine = NavigationEngine("shelves")
        engine.pushContext("grid")
        engine.pushModal("confirm")
        assertTrue(engine.isModalActive)
    }

    @Test
    fun `confirm on a grid cell runs that cell's action`() {
        var picked: String? = null
        val engine = NavigationEngine()
        engine.replaceFlowGrid(grid, grid.keys.map { key -> node(key) { picked = key } })
        engine.markReady()
        engine.dispatch(dir(NavigationDirection.RIGHT))
        engine.dispatch(NavigationCommand.Confirm)
        assertEquals("t1", picked)
    }
}
