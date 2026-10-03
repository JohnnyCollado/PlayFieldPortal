package com.playfieldportal.core.domain.model

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/** The virtual keyboard's own buttons: L2 is Shift and R3 is Caps Lock, by default and under every layout. */
class GamepadKeyboardBindingsTest {

    @Test
    fun `L2 is Shift and R3 is Caps Lock by default`() {
        val mappings = GamepadMappings()
        assertEquals(GamepadAction.SHIFT, mappings.actionFor(KeyEvent.KEYCODE_BUTTON_L2))
        assertEquals(GamepadAction.CAPS_LOCK, mappings.actionFor(KeyEvent.KEYCODE_BUTTON_THUMBR))
    }

    @Test
    fun `the face-button layouts leave them alone`() {
        for (confirm in ConfirmBackLayout.entries) for (xy in XYLayout.entries) {
            val mappings = gamepadMappingsFor(confirm, xy)
            assertEquals(GamepadAction.SHIFT, mappings.actionFor(KeyEvent.KEYCODE_BUTTON_L2))
            assertEquals(GamepadAction.CAPS_LOCK, mappings.actionFor(KeyEvent.KEYCODE_BUTTON_THUMBR))
        }
    }

    @Test
    fun `they name themselves on the remap screen and show their own glyphs`() {
        assertEquals("Shift (Keyboard)", GamepadAction.SHIFT.displayLabel())
        assertEquals("Caps Lock (Keyboard)", GamepadAction.CAPS_LOCK.displayLabel())
        assertEquals(ControllerIcon.TRIGGER_LEFT, GamepadMappings().iconFor(GamepadAction.SHIFT))
        assertEquals(ControllerIcon.STICK_RIGHT_CLICK, GamepadMappings().iconFor(GamepadAction.CAPS_LOCK))
    }
}
