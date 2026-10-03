package com.playfieldportal.core.ui.keyboard

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.sound.MenuSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one keyboard session the app can have open (Virtual Keyboard plan 6.7). */
class VirtualKeyboardControllerTest {

    private val controller = VirtualKeyboardController()

    private val edits = mutableListOf<String>()
    private var dones = 0
    private var closes = 0

    private fun request(
        text: String = "ab",
        placement: KeyboardPlacement = KeyboardPlacement.SETTINGS_FOOTER,
        isPassword: Boolean = false,
        onClose: () -> Unit = { closes++ },
    ) = VirtualKeyboardRequest(
        text = text,
        placement = placement,
        isPassword = isPassword,
        onTextChange = { value, _ -> edits += value },
        onDone = { dones++ },
        onClose = onClose,
    )

    @Test fun `open publishes a session with the request's text, placement and password flag`() {
        controller.open(request(text = "hello", placement = KeyboardPlacement.BELOW_FIELD, isPassword = true))

        val session = controller.session.value
        assertNotNull(session)
        assertEquals("hello", session!!.keyboard.buffer.text)
        assertEquals(KeyboardPlacement.BELOW_FIELD, session.placement)
        assertTrue(session.isPassword)
    }

    @Test fun `actions are consumed only while a session is open`() {
        assertFalse(controller.onGamepadAction(GamepadAction.SELECT))
        controller.open(request())
        assertTrue(controller.onGamepadAction(GamepadAction.NAVIGATE_RIGHT))
        // START (HOME) is the keyboard's too, as Done: it never reaches the notification panel.
        assertTrue(controller.onGamepadAction(GamepadAction.HOME))
    }

    @Test fun `start finishes the edit like the Done key`() {
        controller.open(request())

        controller.onGamepadAction(GamepadAction.HOME)

        assertEquals(1, dones)
        assertNull(controller.session.value)
    }

    @Test fun `each text change reaches the host exactly once`() {
        controller.open(request(text = ""))
        controller.onGamepadAction(GamepadAction.SELECT) // q
        controller.onGamepadAction(GamepadAction.OPEN_CONTEXT_MENU) // space
        controller.onGamepadAction(GamepadAction.NAVIGATE_RIGHT) // focus only

        assertEquals(listOf("q", "q "), edits)
    }

    @Test fun `done calls onDone and clears, close calls onClose and clears`() {
        controller.open(request())
        // From q (row 1), three steps reach the bottom row (Shift); a fourth would cycle to the top.
        repeat(3) { controller.onGamepadAction(GamepadAction.NAVIGATE_DOWN) }
        repeat(4) { controller.onGamepadAction(GamepadAction.NAVIGATE_RIGHT) } // → Done
        controller.onGamepadAction(GamepadAction.SELECT)
        assertEquals(1, dones)
        assertNull(controller.session.value)

        controller.open(request())
        controller.onGamepadAction(GamepadAction.BACK)
        assertEquals(1, closes)
        assertNull(controller.session.value)
    }

    @Test fun `a second open closes the first session before publishing the new one`() {
        var firstClosed = false
        controller.open(request(text = "first", onClose = { firstClosed = true }))
        controller.open(request(text = "second"))

        assertTrue(firstClosed)
        assertEquals("second", controller.session.value?.keyboard?.buffer?.text)
    }

    @Test fun `a stale token cannot close the current session`() {
        val stale = controller.open(request(text = "first", onClose = {}))
        controller.open(request(text = "second"))

        controller.release(stale)

        assertEquals("second", controller.session.value?.keyboard?.buffer?.text)
        assertEquals(0, closes)
    }

    @Test fun `handing over to the system keyboard clears without done or close`() {
        val token = controller.open(request())

        controller.handOverToSystemKeyboard(token)

        assertNull(controller.session.value)
        assertEquals(0, dones)
        assertEquals(0, closes)
    }

    @Test fun `turning the setting off while open closes the session`() {
        controller.open(request())

        controller.setEnabled(false)

        assertNull(controller.session.value)
        assertEquals(1, closes)
        assertEquals(TextInputMode.SYSTEM, controller.modeFor(InputSource.CONTROLLER))
    }

    @Test fun `the input source and setting decide the mode`() {
        controller.inputSource = InputSource.TOUCH
        assertEquals(TextInputMode.SYSTEM, controller.modeFor())
        controller.inputSource = InputSource.CONTROLLER
        assertEquals(TextInputMode.VIRTUAL, controller.modeFor())
    }

    @Test fun `a host-side text change updates the open session`() {
        val token = controller.open(request(text = "abcdef"))

        controller.updateText(token, "ab")

        assertEquals(TextEditBuffer("ab", 2), controller.session.value?.keyboard?.buffer)
    }

    @Test fun `each press's sound reaches the sink`() {
        val sounds = mutableListOf<MenuSound>()
        controller.soundSink = { sounds += it }
        controller.open(request())

        controller.onGamepadAction(GamepadAction.NAVIGATE_RIGHT)
        controller.onGamepadAction(GamepadAction.BACK)

        assertEquals(listOf(MenuSound.SCROLL, MenuSound.BACK), sounds)
    }
}
