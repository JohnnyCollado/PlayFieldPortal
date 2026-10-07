package com.playfieldportal.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.theme.PFPTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A text field's controller [helperPrompt] glyph belongs to the controller family only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h480dp")
class SettingsHelperPromptTouchTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun showField(touch: Boolean) {
        composeRule.setContent {
            PFPTheme {
                CompositionLocalProvider(LocalSettingsLastInputWasTouch provides touch) {
                    SettingsScaffold(title = "Settings", subtitle = "Test", onBack = {}) {
                        val scroll = rememberScrollState()
                        LocalSettingsScrollStateRegistrar.current(scroll)
                        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                            SettingsTextFieldRow(
                                label = "Name",
                                value = "",
                                onValueChange = {},
                                helper = "Shown on the card.",
                                helperPrompt = ControllerPromptItem(GamepadAction.SELECT, "Type"),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `controller mode shows the helper prompt`() {
        showField(touch = false)

        composeRule.onNodeWithText("Type").assertIsDisplayed()
    }

    @Test
    fun `touch mode hides the helper prompt but keeps the helper text`() {
        showField(touch = true)

        assertEquals(0, composeRule.onAllNodesWithText("Type").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Shown on the card.").assertIsDisplayed()
    }
}
