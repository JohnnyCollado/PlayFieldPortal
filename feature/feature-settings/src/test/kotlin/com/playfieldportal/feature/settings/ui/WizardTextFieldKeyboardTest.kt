package com.playfieldportal.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.keyboard.KeyboardPlacement
import com.playfieldportal.core.ui.keyboard.LocalVirtualKeyboard
import com.playfieldportal.core.ui.keyboard.VirtualKeyboardController
import com.playfieldportal.core.ui.keyboard.virtualKeyTag
import com.playfieldportal.core.ui.theme.PFPTheme
import com.playfieldportal.feature.settings.ui.wizard.WizardScaffold
import com.playfieldportal.feature.settings.ui.wizard.WizardTextField
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Initial Setup's text fields with PFP's keyboard (Virtual Keyboard plan T10, wizard row). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h480dp")
class WizardTextFieldKeyboardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val keyboard = VirtualKeyboardController()
    private val pendingAction = mutableStateOf<GamepadAction?>(null)
    private val actions = Channel<GamepadAction>(Channel.UNLIMITED)
    private var consumedPlain = false
    private var text by mutableStateOf("")

    private fun show() {
        composeRule.setContent {
            PFPTheme {
                LaunchedEffect(Unit) { for (action in actions) pendingAction.value = action }
                CompositionLocalProvider(
                    LocalSettingsPendingAction provides pendingAction.value,
                    LocalSettingsActionConsumed provides { consumedPlain = true },
                    LocalVirtualKeyboard provides keyboard,
                ) {
                    WizardScaffold(stepNumber = 8, title = "Initial Setup", heading = "Connect services.", onBack = {}) {
                        WizardTextField(label = "Username", value = text, onValueChange = { text = it })
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun press(action: GamepadAction) {
        if (keyboard.onGamepadAction(action)) {
            composeRule.waitForIdle()
            return
        }
        actions.trySend(action)
        composeRule.waitUntil(10_000) { consumedPlain }
        consumedPlain = false
        pendingAction.value = null
        composeRule.waitForIdle()
    }

    @Test fun `select opens PFP's keyboard in place of the wizard footer`() {
        show()

        press(GamepadAction.SELECT)

        assertEquals(KeyboardPlacement.SETTINGS_FOOTER, keyboard.session.value?.placement)
        composeRule.onNodeWithTag(virtualKeyTag(1, 0)).assertExists()
        composeRule.onNodeWithText("Enter").assertDoesNotExist()
    }

    @Test fun `typing reaches the field and back restores the wizard footer`() {
        show()
        press(GamepadAction.SELECT)
        press(GamepadAction.SELECT) // q

        assertEquals("q", text)

        press(GamepadAction.BACK)
        assertNull(keyboard.session.value)
        composeRule.onNodeWithText("Enter").assertExists()
    }
}
