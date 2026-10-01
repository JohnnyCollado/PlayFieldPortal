package com.playfieldportal.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.keyboard.KeyboardPlacement
import com.playfieldportal.core.ui.keyboard.LocalVirtualKeyboard
import com.playfieldportal.core.ui.keyboard.VirtualKeyboardController
import com.playfieldportal.core.ui.keyboard.virtualKeyTag
import com.playfieldportal.core.ui.theme.PFPTheme
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings text fields with PFP's keyboard (Virtual Keyboard plan 6.9). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h480dp")
class SettingsTextFieldRowKeyboardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val keyboard = VirtualKeyboardController()
    private val pendingAction = mutableStateOf<GamepadAction?>(null)
    private val actions = Channel<GamepadAction>(Channel.UNLIMITED)
    private var consumedPlain = false

    private var text by mutableStateOf("")
    private var showField by mutableStateOf(true)
    private var backs = 0

    private fun showScreen(isPassword: Boolean = false) {
        composeRule.setContent {
            PFPTheme {
                LaunchedEffect(Unit) { for (action in actions) pendingAction.value = action }
                CompositionLocalProvider(
                    LocalSettingsPendingAction provides pendingAction.value,
                    LocalSettingsActionConsumed provides { consumedPlain = true },
                    LocalVirtualKeyboard provides keyboard,
                ) {
                    SettingsScaffold(title = "Settings", subtitle = "Test", onBack = { backs++ }) {
                        val scroll = rememberScrollState()
                        LocalSettingsScrollStateRegistrar.current(scroll)
                        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                            if (showField) {
                                SettingsTextFieldRow(
                                    label = "Add Extension",
                                    value = text,
                                    onValueChange = { text = it },
                                    placeholder = "e.g. iso",
                                    isPassword = isPassword,
                                )
                            }
                            SettingsRow(label = "Next Row", onClick = {})
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** The shell's order: an open keyboard takes the press, else the screen does. */
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

    private val editable = SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, true)

    private fun field() = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))

    @Test fun `controller select opens PFP's keyboard in the footer`() {
        showScreen()

        press(GamepadAction.SELECT)

        val session = keyboard.session.value
        assertNotNull(session)
        assertEquals(KeyboardPlacement.SETTINGS_FOOTER, session!!.placement)
        composeRule.onNodeWithTag(virtualKeyTag(1, 0)).assertExists()
    }

    @Test fun `a tap edits with the system keyboard and opens no session`() {
        showScreen()

        field().performClick()
        composeRule.waitForIdle()

        assertNull(keyboard.session.value)
        field().assert(editable)
    }

    @Test fun `with the setting off, controller select takes today's path`() {
        keyboard.setEnabled(false)
        showScreen()

        press(GamepadAction.SELECT)

        assertNull(keyboard.session.value)
        field().assert(editable)
    }

    @Test fun `keys typed on the keyboard reach the field`() {
        showScreen()
        press(GamepadAction.SELECT)

        press(GamepadAction.SELECT) // q
        press(GamepadAction.NAVIGATE_RIGHT)
        press(GamepadAction.SELECT) // w

        assertEquals("qw", text)
    }

    @Test fun `done ends editing and the cursor carries on from the row`() {
        showScreen()
        press(GamepadAction.SELECT)
        repeat(3) { press(GamepadAction.NAVIGATE_DOWN) }
        repeat(4) { press(GamepadAction.NAVIGATE_RIGHT) }
        press(GamepadAction.SELECT) // Done

        assertNull(keyboard.session.value)
        press(GamepadAction.NAVIGATE_DOWN)
        composeRule.onNode(isFocused()).assert(hasText("Next Row") or hasAnyDescendant(hasText("Next Row")))
    }

    @Test fun `back closes only the keyboard, a second back leaves the screen`() {
        showScreen()
        press(GamepadAction.SELECT)

        press(GamepadAction.BACK)
        assertNull(keyboard.session.value)
        assertEquals(0, backs)

        press(GamepadAction.BACK)
        assertEquals(1, backs)
    }

    @Test fun `the footer shows the keyboard prompts while open and the screen's after`() {
        showScreen()
        press(GamepadAction.SELECT)
        listOf("Type", "Delete", "Space", "Cursor", "Close").forEach {
            composeRule.onNodeWithText(it).assertExists()
        }

        press(GamepadAction.BACK)
        composeRule.onNodeWithText("Close").assertDoesNotExist()
        composeRule.onNodeWithText("Enter").assertExists()
    }

    @Test fun `the edited field stays above the keyboard`() {
        showScreen()
        press(GamepadAction.SELECT)
        composeRule.waitForIdle()

        val fieldBottom = field().fetchSemanticsNode().boundsInRoot.bottom
        val panelTop = composeRule.onNodeWithTag(virtualKeyTag(0, 0)).fetchSemanticsNode().boundsInRoot.top
        assertTrue("field bottom $fieldBottom must sit above the panel top $panelTop", fieldBottom < panelTop)
    }

    @Test fun `removing the field closes its session`() {
        showScreen()
        press(GamepadAction.SELECT)
        assertNotNull(keyboard.session.value)

        showField = false
        composeRule.waitForIdle()

        assertNull(keyboard.session.value)
    }

    @Test fun `a password field keeps its mask while typed into`() {
        showScreen(isPassword = true)
        press(GamepadAction.SELECT)
        press(GamepadAction.SELECT) // q

        assertEquals("q", text)
        field().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }
}
