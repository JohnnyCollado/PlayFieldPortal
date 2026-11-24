package com.playfieldportal.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.playfieldportal.core.ui.theme.PFPTheme
import com.playfieldportal.feature.settings.ui.wizard.WizardRow
import com.playfieldportal.feature.settings.ui.wizard.WizardScaffold
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The wizard header names how many pages this run shows ("Step N of M"), by pad and by touch. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class WizardStepCountTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(touch: Boolean, stepCount: Int?) {
        composeRule.setContent {
            PFPTheme {
                CompositionLocalProvider(LocalSettingsLastInputWasTouch provides touch) {
                    WizardScaffold(
                        stepNumber = 3,
                        stepCount = stepCount,
                        title = "Initial Setup",
                        heading = "Choose your music folders.",
                        onBack = {},
                    ) {
                        WizardRow(label = "Continue", onClick = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `the header shows the step total with a controller`() {
        show(touch = false, stepCount = 9)
        composeRule.onNodeWithText("Step 3 of 9").assertIsDisplayed()
    }

    @Test
    fun `the header shows the step total with touch`() {
        show(touch = true, stepCount = 12)
        composeRule.onNodeWithText("Step 3 of 12").assertIsDisplayed()
    }

    @Test
    fun `no total is shown when the caller gives none`() {
        show(touch = false, stepCount = null)
        assertEquals(0, composeRule.onAllNodesWithText("Step 3", substring = true).fetchSemanticsNodes().size)
    }
}
