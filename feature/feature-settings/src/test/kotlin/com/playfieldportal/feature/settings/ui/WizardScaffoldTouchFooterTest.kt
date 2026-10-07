package com.playfieldportal.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/**
 * The wizard footer in touch mode: the controller glyph row is replaced by tappable Back / Skip
 * pills (Enter has no pill — the rows themselves tap), so a touch user can turn the page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class WizardScaffoldTouchFooterTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var backs = 0
    private var skips = 0

    private fun showWizard(
        touch: Boolean,
        onSkip: (() -> Unit)? = { skips++ },
        backEnabled: Boolean = true,
    ) {
        composeRule.setContent {
            PFPTheme {
                CompositionLocalProvider(LocalSettingsLastInputWasTouch provides touch) {
                    WizardScaffold(
                        stepNumber = 2,
                        title = "Initial Setup",
                        heading = "Choose your controller.",
                        onBack = { backs++ },
                        backEnabled = backEnabled,
                        onSkip = onSkip,
                    ) {
                        WizardRow(label = "Continue", sublabel = "Next: ROM folders", onClick = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `touch footer drops the Enter prompt and keeps tappable Back and Skip`() {
        showWizard(touch = true)

        assertEquals(0, composeRule.onAllNodesWithText("Enter").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Skip").assertIsDisplayed()
    }

    @Test
    fun `tapping the touch Back and Skip pills runs the wizard actions`() {
        showWizard(touch = true)

        composeRule.onNodeWithText("Skip").performClick()
        composeRule.onNodeWithText("Back").performClick()

        assertEquals(1, skips)
        assertEquals(1, backs)
    }

    @Test
    fun `touch Back is inert on the first page`() {
        showWizard(touch = true, backEnabled = false)

        composeRule.onNodeWithText("Back").performClick()

        assertEquals(0, backs)
    }

    @Test
    fun `touch footer shows no Skip pill on a page without Skip`() {
        showWizard(touch = true, onSkip = null)

        assertEquals(0, composeRule.onAllNodesWithText("Skip").fetchSemanticsNodes().size)
    }

    @Test
    fun `controller footer keeps the Enter prompt`() {
        showWizard(touch = false)

        composeRule.onNodeWithText("Enter").assertIsDisplayed()
    }
}
