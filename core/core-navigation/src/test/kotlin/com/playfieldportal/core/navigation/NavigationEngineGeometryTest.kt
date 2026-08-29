package com.playfieldportal.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec §7/§8: visual (geometry) order wins over registration order; removal recovers to the
 * nearest focusable neighbor by visual position; order fallback when geometry is unavailable.
 */
class NavigationEngineGeometryTest {

    private fun node(key: String, onSelect: (() -> Unit)? = null) = NavigationNode(key = key, onSelect = onSelect)

    @Test
    fun `visual order wins over registration order`() {
        val engine = NavigationEngine()
        // Registered out of visual order (async insertion, spec §8).
        engine.replaceNodesWithGeometry(
            listOf(node("late"), node("first"), node("middle")),
            geometry = mapOf("first" to 100f, "middle" to 200f, "late" to 300f),
        )
        engine.markReady()

        assertEquals("first", engine.focusedKey)
        assertEquals("middle", engine.dispatch(NavigationCommand.Direction(NavigationDirection.DOWN)))
        assertEquals("late", engine.dispatch(NavigationCommand.Direction(NavigationDirection.DOWN)))
        assertEquals("middle", engine.dispatch(NavigationCommand.Direction(NavigationDirection.UP)))
    }

    @Test
    fun `order fallback when geometry is unavailable`() {
        val engine = NavigationEngine()
        engine.replaceNodes(listOf(node("a"), node("b"), node("c")))
        engine.markReady()
        assertEquals("a", engine.focusedKey)
        assertEquals("b", engine.dispatch(NavigationCommand.Direction(NavigationDirection.DOWN)))
        assertEquals("c", engine.dispatch(NavigationCommand.Direction(NavigationDirection.DOWN)))
    }

    @Test
    fun `removal recovers to nearest focusable neighbor by visual position`() {
        val engine = NavigationEngine()
        engine.replaceNodesWithGeometry(
            listOf(node("a"), node("b"), node("c"), node("d")),
            geometry = mapOf("a" to 100f, "b" to 200f, "c" to 300f, "d" to 400f),
        )
        engine.markReady()
        engine.setFocused("c")

        // Remove 'c'; its visual neighbours are 'b' (200) and 'd' (400) — both 100 away, so
        // the stable tie-break (registration order) picks the earlier node.
        engine.replaceNodesWithGeometry(
            listOf(node("a"), node("b"), node("d")),
            geometry = mapOf("a" to 100f, "b" to 200f, "d" to 400f),
            previousGeometry = mapOf("a" to 100f, "b" to 200f, "c" to 300f, "d" to 400f),
        )
        assertEquals("b", engine.focusedKey)
    }

    @Test
    fun `removal falls back to order when no geometry existed`() {
        val engine = NavigationEngine()
        engine.replaceNodes(listOf(node("a"), node("b"), node("c"), node("d")))
        engine.markReady()
        engine.dispatch(NavigationCommand.Direction(NavigationDirection.DOWN))
        engine.dispatch(NavigationCommand.Direction(NavigationDirection.DOWN))
        engine.replaceNodes(listOf(node("a"), node("b"), node("d")))
        assertEquals("d", engine.focusedKey)
    }

    @Test
    fun `unpositioned nodes keep registration order after positioned ones`() {
        val engine = NavigationEngine()
        engine.replaceNodesWithGeometry(
            listOf(node("first"), node("unpositioned"), node("second")),
            geometry = mapOf("first" to 100f, "second" to 200f),
        )
        engine.markReady()
        assertEquals("first", engine.focusedKey)
        assertEquals("second", engine.dispatch(NavigationCommand.Direction(NavigationDirection.DOWN)))
        assertEquals("unpositioned", engine.dispatch(NavigationCommand.Direction(NavigationDirection.DOWN)))
    }
}
