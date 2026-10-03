package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.keyboard.InputSource
import com.playfieldportal.core.ui.keyboard.KeyboardPlacement
import com.playfieldportal.core.ui.keyboard.VirtualKeyboardController
import com.playfieldportal.core.ui.keyboard.VirtualKeyboardRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shell's keyboard tier (Virtual Keyboard plan 6.8, shell items). The dispatcher hands every
 * press to [keyboardCaptures] before any other tier, and counts an open keyboard as a blocking
 * overlay so nothing behind it moves.
 */
class VirtualKeyboardShellRoutingTest {

    private val controller = VirtualKeyboardController()

    private fun openKeyboard() = controller.open(
        VirtualKeyboardRequest(
            text = "",
            placement = KeyboardPlacement.SETTINGS_FOOTER,
            onTextChange = { _, _ -> },
            onDone = {},
            onClose = {},
        ),
    )

    @Test fun `an open keyboard is a blocking overlay`() {
        assertTrue(XMBUiState(showBootSequence = false, virtualKeyboardOpen = true).hasBlockingOverlay)
        assertFalse(XMBUiState(showBootSequence = false).hasBlockingOverlay)
    }

    @Test fun `with the keyboard open over settings, every press is the keyboard's`() {
        openKeyboard()
        GamepadAction.entries.forEach { action ->
            openKeyboard()
            assertTrue(keyboardCaptures(controller, action), action.name)
        }
    }

    @Test fun `the shoulders reach the keyboard as caret moves`() {
        openKeyboard()
        assertTrue(keyboardCaptures(controller, GamepadAction.PREV_CATEGORY))
        assertTrue(keyboardCaptures(controller, GamepadAction.NEXT_CATEGORY))
    }

    @Test fun `with no keyboard open, presses fall through to the other tiers`() {
        GamepadAction.entries.forEach { assertFalse(keyboardCaptures(controller, it), it.name) }
    }

    @Test fun `the last input source is mirrored into the keyboard`() {
        mirrorInputSource(controller, touch = true)
        assertEquals(InputSource.TOUCH, controller.inputSource)
        mirrorInputSource(controller, touch = false)
        assertEquals(InputSource.CONTROLLER, controller.inputSource)
    }
}
