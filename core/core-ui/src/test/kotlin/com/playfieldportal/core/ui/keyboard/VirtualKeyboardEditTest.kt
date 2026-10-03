package com.playfieldportal.core.ui.keyboard

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.theme.PFPTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The shared field wiring for PFP's keyboard (Virtual Keyboard plan T10). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h468dp")
class VirtualKeyboardEditTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val keyboard = VirtualKeyboardController()
    private var text by mutableStateOf("ab")
    private var shown by mutableStateOf(true)
    private var dones = 0
    private var closes = 0
    private lateinit var edit: VirtualKeyboardEdit

    private fun show(provide: Boolean = true) {
        composeRule.setContent {
            PFPTheme {
                CompositionLocalProvider(LocalVirtualKeyboard provides keyboard.takeIf { provide }) {
                    if (shown) {
                        edit = rememberVirtualKeyboardEdit(
                            text = text,
                            onTextChange = { text = it },
                            placement = KeyboardPlacement.BOTTOM_CENTER,
                            onDone = { dones++ },
                            onClose = { closes++ },
                        )
                        VirtualKeyboardTextInput(edit) {
                            BasicTextField(
                                value = edit.fieldValue,
                                onValueChange = edit::onFieldValueChange,
                                modifier = Modifier.virtualKeyboardField(edit).testTag("field"),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun press(action: GamepadAction) {
        keyboard.onGamepadAction(action)
        composeRule.waitForIdle()
    }

    @Test fun `a controller start opens PFP's keyboard and reports it took the edit`() {
        keyboard.inputSource = InputSource.CONTROLLER
        show()

        composeRule.runOnIdle { assertTrue(edit.start()) }

        assertNotNull(keyboard.session.value)
        assertTrue(edit.isOpen)
        assertEquals(KeyboardPlacement.BOTTOM_CENTER, keyboard.session.value?.placement)
    }

    @Test fun `a touch start leaves the edit to the system keyboard`() {
        keyboard.inputSource = InputSource.TOUCH
        show()

        composeRule.runOnIdle { assertFalse(edit.start()) }

        assertNull(keyboard.session.value)
    }

    @Test fun `no keyboard provided means the system keyboard`() {
        show(provide = false)

        composeRule.runOnIdle { assertFalse(edit.start(InputSource.CONTROLLER)) }
    }

    @Test fun `typed keys reach the host and move the field's caret`() {
        show()
        composeRule.runOnIdle { edit.start(InputSource.CONTROLLER) }

        press(GamepadAction.SELECT) // q

        assertEquals("abq", text)
        assertEquals(3, edit.fieldValue.selection.end)
    }

    @Test fun `done and close reach the host and end the session`() {
        show()
        composeRule.runOnIdle { edit.start(InputSource.CONTROLLER) }
        press(GamepadAction.BACK)
        assertEquals(1, closes)
        assertFalse(edit.isOpen)

        composeRule.runOnIdle { edit.start(InputSource.CONTROLLER) }
        repeat(3) { press(GamepadAction.NAVIGATE_DOWN) }
        repeat(4) { press(GamepadAction.NAVIGATE_RIGHT) }
        press(GamepadAction.SELECT)
        assertEquals(1, dones)
    }

    @Test fun `a tap on the field hands over to the system keyboard`() {
        show()
        composeRule.runOnIdle { edit.start(InputSource.CONTROLLER) }

        composeRule.onNodeWithTag("field").performClick()
        composeRule.waitForIdle()

        assertNull(keyboard.session.value)
        assertEquals(0, closes)
    }

    @Test fun `closing PFP's keyboard keeps the system keyboard held while the field is still focused`() {
        show()
        composeRule.runOnIdle { edit.start(InputSource.CONTROLLER) }

        press(GamepadAction.BACK)

        assertFalse(edit.isOpen)
        // The field may keep focus (a host that moves its cursor elsewhere later): its own
        // keyboard request must not slip through the moment the session ends.
        assertTrue(edit.holdsSystemKeyboard)
    }

    @Test fun `stopping an open edit keeps the hold too`() {
        show()
        composeRule.runOnIdle { edit.start(InputSource.CONTROLLER) }

        composeRule.runOnIdle { edit.stop() }

        assertTrue(edit.holdsSystemKeyboard)
    }

    @Test fun `a tap after the keyboard closed lifts the hold for the system keyboard`() {
        show()
        composeRule.runOnIdle { edit.start(InputSource.CONTROLLER) }
        press(GamepadAction.BACK)

        composeRule.onNodeWithTag("field").performClick()
        composeRule.waitForIdle()

        assertFalse(edit.holdsSystemKeyboard)
    }

    @Test fun `a fresh touch edit holds nothing`() {
        keyboard.inputSource = InputSource.TOUCH
        show()

        composeRule.runOnIdle { edit.start() }

        assertFalse(edit.holdsSystemKeyboard)
    }

    @Test fun `a host-side text change follows into the open keyboard`() {
        show()
        composeRule.runOnIdle { edit.start(InputSource.CONTROLLER) }

        text = "a"
        composeRule.waitForIdle()

        assertEquals("a", keyboard.session.value?.keyboard?.buffer?.text)
    }

    @Test fun `leaving composition releases the session quietly`() {
        show()
        composeRule.runOnIdle { edit.start(InputSource.CONTROLLER) }

        shown = false
        composeRule.waitForIdle()

        assertNull(keyboard.session.value)
        assertEquals(0, closes)
    }

    @Test fun `stop drops the session without done or close`() {
        show()
        composeRule.runOnIdle { edit.start(InputSource.CONTROLLER) }

        composeRule.runOnIdle { edit.stop() }

        assertNull(keyboard.session.value)
        assertEquals(0, closes + dones)
    }
}
