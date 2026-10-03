package com.playfieldportal.feature.xmb.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.keyboard.InputSource
import com.playfieldportal.core.ui.keyboard.KeyboardPlacement
import com.playfieldportal.core.ui.keyboard.LocalVirtualKeyboard
import com.playfieldportal.core.ui.keyboard.VirtualKeyboardController
import com.playfieldportal.core.ui.theme.PFPTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Games ▸ Search with PFP's keyboard (Virtual Keyboard plan 6.8, game search items). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h468dp")
class GameSearchFieldKeyboardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val keyboard = VirtualKeyboardController()
    private var text by mutableStateOf("zel")
    private var open by mutableStateOf(true)
    private var confirms = 0
    private var cancels = 0

    private fun show() {
        composeRule.setContent {
            PFPTheme {
                CompositionLocalProvider(LocalVirtualKeyboard provides keyboard) {
                    if (open) {
                        GameSearchField(
                            text = text,
                            onTextChange = { text = it },
                            onConfirm = { confirms++; open = false },
                            onCancel = { cancels++; open = false },
                        )
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

    @Test fun `opened by controller with the setting on, the keyboard hangs under the field`() {
        keyboard.inputSource = InputSource.CONTROLLER
        show()

        val session = keyboard.session.value
        assertNotNull(session)
        assertEquals(KeyboardPlacement.BELOW_FIELD, session!!.placement)
        assertNotNull(session.anchor)
        assertEquals("zel", session.keyboard.buffer.text)
    }

    @Test fun `select types into the live query instead of confirming`() {
        keyboard.inputSource = InputSource.CONTROLLER
        show()

        press(GamepadAction.SELECT) // q

        assertEquals("zelq", text)
        assertEquals(0, confirms)
    }

    @Test fun `done confirms the search`() {
        keyboard.inputSource = InputSource.CONTROLLER
        show()
        repeat(3) { press(GamepadAction.NAVIGATE_DOWN) }
        repeat(4) { press(GamepadAction.NAVIGATE_RIGHT) }

        press(GamepadAction.SELECT)

        assertEquals(1, confirms)
        assertNull(keyboard.session.value)
    }

    @Test fun `back cancels so the opening query can be restored`() {
        keyboard.inputSource = InputSource.CONTROLLER
        show()

        press(GamepadAction.BACK)

        assertEquals(1, cancels)
        assertEquals(0, confirms)
    }

    @Test fun `opened by touch it keeps the system keyboard`() {
        keyboard.inputSource = InputSource.TOUCH
        show()

        assertNull(keyboard.session.value)
    }

    @Test fun `with the setting off a controller-opened search keeps the system keyboard`() {
        keyboard.setEnabled(false)
        keyboard.inputSource = InputSource.CONTROLLER
        show()

        assertNull(keyboard.session.value)
    }

    @Test fun `closing the field drops its session`() {
        keyboard.inputSource = InputSource.CONTROLLER
        show()

        open = false
        composeRule.waitForIdle()

        assertNull(keyboard.session.value)
    }
}
