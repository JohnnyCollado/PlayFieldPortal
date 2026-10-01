package com.playfieldportal.core.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.keyboard.InputSource
import com.playfieldportal.core.ui.keyboard.KeyboardPlacement
import com.playfieldportal.core.ui.keyboard.LocalVirtualKeyboard
import com.playfieldportal.core.ui.keyboard.VirtualKeyboardController
import com.playfieldportal.core.ui.theme.PFPTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shared name modal with PFP's keyboard (Virtual Keyboard plan T10): Collections, Game and
 * App Detail's note, title, display-name and new-collection entries all run through it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h468dp")
class PfpTextEntryModalKeyboardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val keyboard = VirtualKeyboardController()
    private var text by mutableStateOf("")
    private var focus by mutableStateOf(PfpModalFocus.FIELD)
    private var confirms = 0
    private var cancels = 0

    private fun show(maxLength: Int? = null) {
        composeRule.setContent {
            PFPTheme {
                CompositionLocalProvider(LocalVirtualKeyboard provides keyboard) {
                    PfpTextEntryModal(
                        title = "New Collection",
                        value = text,
                        onValueChange = { text = it },
                        focus = focus,
                        onFocusChange = { focus = it },
                        onConfirm = { confirms++ },
                        onCancel = { cancels++ },
                        maxLength = maxLength,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun press(action: GamepadAction) {
        keyboard.onGamepadAction(action)
        composeRule.waitForIdle()
    }

    private fun toDone() {
        repeat(3) { press(GamepadAction.NAVIGATE_DOWN) }
        repeat(4) { press(GamepadAction.NAVIGATE_RIGHT) }
    }

    @Test fun `opened from the controller, the field types on PFP's keyboard`() {
        keyboard.inputSource = InputSource.CONTROLLER
        show()

        assertEquals(KeyboardPlacement.BOTTOM_CENTER, keyboard.session.value?.placement)
        press(GamepadAction.SELECT) // q
        assertEquals("q", text)
    }

    @Test fun `opened by touch, the field keeps the system keyboard`() {
        keyboard.inputSource = InputSource.TOUCH
        show()

        assertNull(keyboard.session.value)
    }

    @Test fun `done saves a valid name`() {
        keyboard.inputSource = InputSource.CONTROLLER
        text = "RPGs"
        show()
        toDone()

        press(GamepadAction.SELECT)

        assertEquals(1, confirms)
    }

    @Test fun `done on an empty name moves to Cancel instead of saving`() {
        keyboard.inputSource = InputSource.CONTROLLER
        show()
        toDone()

        press(GamepadAction.SELECT)

        assertEquals(0, confirms)
        assertEquals(PfpModalFocus.CANCEL, focus)
    }

    @Test fun `back closes only the keyboard and moves to the buttons`() {
        keyboard.inputSource = InputSource.CONTROLLER
        text = "RPGs"
        show()

        press(GamepadAction.BACK)

        assertNull(keyboard.session.value)
        assertEquals(0, cancels)
        assertEquals(PfpModalFocus.CONFIRM, focus)
    }

    @Test fun `returning to the field reopens the keyboard`() {
        keyboard.inputSource = InputSource.CONTROLLER
        text = "RPGs"
        show()
        press(GamepadAction.BACK)

        focus = PfpModalFocus.FIELD
        composeRule.waitForIdle()

        assertTrue(keyboard.isOpen)
    }

    @Test fun `the max length holds on PFP's keyboard`() {
        keyboard.inputSource = InputSource.CONTROLLER
        text = "abc"
        show(maxLength = 3)

        press(GamepadAction.SELECT)

        assertEquals("abc", text)
    }

    @Test fun `a multiline entry keeps line breaks out of Done and wraps its text`() {
        keyboard.inputSource = InputSource.TOUCH
        text = "A long description that will certainly need more than one line to show in full on the card"
        composeRule.setContent {
            PFPTheme {
                CompositionLocalProvider(LocalVirtualKeyboard provides keyboard) {
                    PfpTextEntryModal(
                        title = "Edit Description",
                        value = text,
                        onValueChange = { text = it },
                        focus = PfpModalFocus.CONFIRM,
                        onFocusChange = {},
                        onConfirm = {},
                        onCancel = {},
                        multiline = true,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val field = composeRule.onNodeWithTag(PfpModalTags.FIELD).fetchSemanticsNode()
        val oneLine = with(composeRule.density) { 48.dp.toPx() }
        assertTrue("a multiline field grows past one line (${field.size.height} vs $oneLine)", field.size.height > oneLine)
    }

    @Test fun `the card sits above the open keyboard`() {
        keyboard.inputSource = InputSource.CONTROLLER
        show()

        val cardBottom = composeRule.onNodeWithTag(PfpModalTags.CARD).fetchSemanticsNode().boundsInRoot.bottom
        val rootHeight = composeRule.onNodeWithTag(PfpModalTags.SCRIM).fetchSemanticsNode().boundsInRoot.bottom
        val reserved = with(composeRule.density) { com.playfieldportal.core.ui.keyboard.VirtualKeyboardBottomReserve.toPx() }
        assertTrue("card bottom $cardBottom must clear the keyboard (${rootHeight - reserved})", cardBottom <= rootHeight - reserved)
    }
}
