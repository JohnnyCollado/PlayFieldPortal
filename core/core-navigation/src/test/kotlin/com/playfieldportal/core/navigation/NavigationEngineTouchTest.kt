package com.playfieldportal.core.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEngineTouchTest {
    @Test
    fun `tap selects node and hides cursor`() {
        var selected = false
        val engine = NavigationEngine()
        engine.replaceNodes(listOf(NavigationNode("a", onSelect = { selected = true })))
        engine.markReady()

        assertTrue(engine.dispatchTouch("a", NavigationTouchAction.TAP))
        assertTrue(selected)
        assertFalse(engine.cursorVisible)

        engine.dispatch(NavigationCommand.Direction(NavigationDirection.DOWN))
        assertTrue(engine.cursorVisible)
    }

    @Test
    fun `long press opens menu without selecting`() {
        var selected = false
        var menuOpened = false
        val engine = NavigationEngine()
        engine.replaceNodes(listOf(NavigationNode(
            "a",
            onSelect = { selected = true },
            onLongPress = { menuOpened = true },
        )))
        engine.markReady()

        assertTrue(engine.dispatchTouch("a", NavigationTouchAction.LONG_PRESS))
        assertTrue(menuOpened)
        assertFalse(selected)
        assertFalse(engine.cursorVisible)
    }

    @Test
    fun `long press without menu is a no-op after hiding cursor`() {
        val engine = NavigationEngine()
        engine.replaceNodes(listOf(NavigationNode("a", onSelect = { })))
        engine.markReady()

        assertFalse(engine.dispatchTouch("a", NavigationTouchAction.LONG_PRESS))
        assertFalse(engine.cursorVisible)
    }
}
