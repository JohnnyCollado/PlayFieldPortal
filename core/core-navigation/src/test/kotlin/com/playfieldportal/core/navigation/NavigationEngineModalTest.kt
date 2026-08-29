package com.playfieldportal.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §15/§16: modal navigation priority, context stacking, and exact focus restoration
 * on close — including the destructive-action fallback to the nearest focusable neighbor.
 */
class NavigationEngineModalTest {

    private fun node(
        key: String,
        onSelect: (() -> Unit)? = null,
        onEditStart: (() -> EditModeHandler?)? = null,
    ) = NavigationNode(key = key, onSelect = onSelect, onEditStart = onEditStart)

    @Test
    fun `modal receives all navigation while underlying graph is paused`() {
        val engine = NavigationEngine()
        engine.replaceNodes(listOf(node("a"), node("b"), node("c")))
        engine.markReady()
        engine.dispatch(NavigationCommand.Direction(NavigationDirection.DOWN))
        assertEquals("b", engine.focusedKey)

        engine.pushModal("modal:confirm")
        engine.replaceNodes(listOf(node("mYes"), node("mNo")))
        assertEquals("mYes", engine.focusedKey)
        assertTrue(engine.isModalActive)

        // Modal navigation moves within the modal, never the underlying graph.
        assertEquals("mNo", engine.dispatch(NavigationCommand.Direction(NavigationDirection.DOWN)))
        assertEquals("mNo", engine.focusedKey)
        assertEquals("mYes", engine.dispatch(NavigationCommand.Direction(NavigationDirection.UP)))

        // Closing restores the exact previous focus ('b') on the resumed graph.
        assertEquals("b", engine.popContext())
        assertFalse(engine.isModalActive)
        assertEquals("b", engine.focusedKey)
    }

    @Test
    fun `modal input never leaks into underlying graph`() {
        val engine = NavigationEngine()
        engine.replaceNodes(listOf(node("a"), node("b")))
        engine.markReady()
        assertEquals("a", engine.focusedKey)

        engine.pushModal("modal:info")
        engine.replaceNodes(listOf(node("mOk")))
        engine.dispatch(NavigationCommand.Direction(NavigationDirection.DOWN))
        engine.dispatch(NavigationCommand.Direction(NavigationDirection.DOWN))
        // Underlying context still focused on 'a'; modal clamped on its single node.
        assertEquals("mOk", engine.focusedKey)

        engine.popContext()
        assertEquals("a", engine.focusedKey)
    }

    @Test
    fun `destructive modal action removes node and focus falls back after settle`() {
        val engine = NavigationEngine()
        engine.replaceNodes(listOf(node("a"), node("b"), node("c")))
        engine.markReady()
        engine.dispatch(NavigationCommand.Direction(NavigationDirection.DOWN))
        assertEquals("b", engine.focusedKey)

        engine.pushModal("modal:delete")
        engine.replaceNodes(listOf(node("mConfirm"), node("mCancel")))
        engine.setFocused("mConfirm")

        // Confirm performs the destructive action: 'b' is deleted from the underlying graph
        // (simulating what the screen does after the modal closes and layout settles).
        engine.dispatch(NavigationCommand.Confirm)
        engine.popContext()
        assertEquals("b", engine.focusedKey)  // restored, node still present at this instant

        // Layout settles: the screen reports the updated node list without 'b'.
        engine.replaceNodes(listOf(node("a"), node("c")))
        assertEquals("nearest survivor by order", "c", engine.focusedKey)
    }

    @Test
    fun `modal with edit mode back exits edit before closing modal`() {
        val engine = NavigationEngine()
        engine.replaceNodes(listOf(node("a")))
        engine.markReady()

        engine.pushModal("modal:picker")
        engine.replaceNodes(
            listOf(
                node("mItem", onEditStart = { object : EditModeHandler {
                    override fun onDirection(direction: NavigationDirection) = true
                    override fun onConfirm() = true
                    override fun onExit() { }
                } }),
            ),
        )
        engine.dispatch(NavigationCommand.Confirm)
        assertTrue(engine.isEditing)

        engine.dispatch(NavigationCommand.Back)
        assertFalse("Back exits edit mode inside the modal", engine.isEditing)
        assertTrue("modal stays open after edit exit", engine.isModalActive)

        engine.popContext()
        assertFalse(engine.isModalActive)
    }
}
