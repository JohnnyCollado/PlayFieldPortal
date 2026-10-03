package com.playfieldportal.core.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which keyboard an edit gets (Virtual Keyboard plan section 1, tests 6.6). */
class TextInputModeTest {

    @Test fun `controller with the setting on gets PFP's keyboard`() {
        assertEquals(TextInputMode.VIRTUAL, resolveTextInputMode(InputSource.CONTROLLER, virtualKeyboardEnabled = true))
    }

    @Test fun `controller with the setting off gets the system keyboard`() {
        assertEquals(TextInputMode.SYSTEM, resolveTextInputMode(InputSource.CONTROLLER, virtualKeyboardEnabled = false))
    }

    @Test fun `touch always gets the system keyboard`() {
        assertEquals(TextInputMode.SYSTEM, resolveTextInputMode(InputSource.TOUCH, virtualKeyboardEnabled = true))
        assertEquals(TextInputMode.SYSTEM, resolveTextInputMode(InputSource.TOUCH, virtualKeyboardEnabled = false))
    }
}
