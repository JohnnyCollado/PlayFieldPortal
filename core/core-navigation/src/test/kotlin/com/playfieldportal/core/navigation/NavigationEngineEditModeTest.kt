package com.playfieldportal.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §5: component-owned edit mode. Confirm enters edit mode, directional input is delegated
 * to the component, and Back ALWAYS exits edit mode before any screen navigation.
 */
class NavigationEngineEditModeTest {

    private fun node(
        key: String,
        onSelect: (() -> Unit)? = null,
        onEditStart: (() -> EditModeHandler?)? = null,
    ) = NavigationNode(key = key, onSelect = onSelect, onEditStart = onEditStart)

    private fun handler(
        consumed: Boolean = true,
        onDirection: (NavigationDirection) -> Boolean = { consumed },
    ) = object : EditModeHandler {
        override fun onDirection(direction: NavigationDirection) = onDirection(direction)
        override fun onConfirm() = true
        override fun onExit() { }
    }

    @Test
    fun `confirm on editable node enters edit mode instead of selecting`() {
        var selected = false
        var entered = false
        val engine = NavigationEngine()
        engine.replaceNodes(
            listOf(
                node("plain", onSelect = { selected = true }),
                node("slider", onEditStart = { entered = true; handler() }),
            ),
        )
        engine.markReady()

        engine.setFocused("plain")
        engine.dispatch(NavigationCommand.Confirm)
        assertTrue("plain node should select", selected)
        assertFalse(engine.isEditing)

        engine.setFocused("slider")
        engine.dispatch(NavigationCommand.Confirm)
        assertTrue("edit mode should have started", entered)
        assertTrue(engine.isEditing)
    }

    @Test
    fun `directional input is delegated to the edit handler while editing`() {
        val received = mutableListOf<NavigationDirection>()
        val engine = NavigationEngine()
        engine.replaceNodes(
            listOf(
                node("slider", onEditStart = { handler(onDirection = { received.add(it); true }) }),
            ),
        )
        engine.markReady()
        engine.setFocused("slider")
        engine.dispatch(NavigationCommand.Confirm)
        assertTrue(engine.isEditing)

        engine.dispatch(NavigationCommand.Direction(NavigationDirection.UP))
        engine.dispatch(NavigationCommand.Direction(NavigationDirection.RIGHT))
        // While editing, the underlying list focus must NOT have moved.
        assertEquals("slider", engine.focusedKey)
        assertEquals(listOf(NavigationDirection.UP, NavigationDirection.RIGHT), received)
    }

    @Test
    fun `back exits edit mode first and second back navigates`() {
        var exits = 0
        var backCount = 0
        val engine = NavigationEngine()
        engine.replaceNodes(
            listOf(
                node("slider", onEditStart = { object : EditModeHandler {
                    override fun onDirection(direction: NavigationDirection) = true
                    override fun onConfirm() = true
                    override fun onExit() { exits++ }
                } }),
            ),
        )
        engine.markReady()
        engine.backHandler = { backCount++ }
        engine.setFocused("slider")
        engine.dispatch(NavigationCommand.Confirm)
        assertTrue(engine.isEditing)

        engine.dispatch(NavigationCommand.Back)
        assertFalse("first Back must exit edit mode", engine.isEditing)
        assertEquals(1, exits)
        assertEquals("first Back must not navigate away", 0, backCount)

        engine.dispatch(NavigationCommand.Back)
        assertEquals("second Back performs screen navigation", 1, backCount)
    }

    @Test
    fun `unconsumed direction inside edit mode falls through to traversal`() {
        val engine = NavigationEngine()
        engine.replaceNodes(
            listOf(
                node("a"),
                node("slider", onEditStart = { handler(consumed = false) }),
            ),
        )
        engine.markReady()
        engine.setFocused("slider")
        engine.dispatch(NavigationCommand.Confirm)
        // Handler declines the direction → engine falls through to normal traversal.
        assertEquals("a", engine.dispatch(NavigationCommand.Direction(NavigationDirection.UP)))
    }

    @Test
    fun `focus loss clears edit mode`() {
        var exits = 0
        val engine = NavigationEngine()
        engine.replaceNodes(
            listOf(
                node("a"),
                node("slider", onEditStart = { object : EditModeHandler {
                    override fun onDirection(direction: NavigationDirection) = true
                    override fun onConfirm() = true
                    override fun onExit() { exits++ }
                } }),
            ),
        )
        engine.markReady()
        engine.setFocused("slider")
        engine.dispatch(NavigationCommand.Confirm)
        assertTrue(engine.isEditing)

        engine.setFocused("a")  // UI reports focus moved away (touch)
        assertFalse(engine.isEditing)
        assertEquals(1, exits)
    }
}
