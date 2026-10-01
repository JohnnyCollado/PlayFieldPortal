package com.playfieldportal.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.theme.PFPTheme
import com.playfieldportal.feature.settings.ui.wizard.WizardRow
import com.playfieldportal.feature.settings.ui.wizard.WizardScaffold
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The wizard footer (setup-wizard plan section 3.7): ◀ ▶ never turn the page — they belong to a
 * row's inline actions — RB skips to the next page, and the footer reads Ⓐ Enter · Ⓑ Back · RB Skip.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class WizardScaffoldNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val pendingAction = mutableStateOf<GamepadAction?>(null)
    private val actions = Channel<GamepadAction>(Channel.UNLIMITED)
    private var consumedPlain = false

    private var backs = 0
    private var skips = 0
    private var selects = 0

    private fun showWizard(
        onSkip: (() -> Unit)? = { skips++ },
        backEnabled: Boolean = true,
        confirmLabel: String = "Enter",
    ) {
        composeRule.setContent {
            PFPTheme {
                LaunchedEffect(Unit) {
                    for (action in actions) pendingAction.value = action
                }
                CompositionLocalProvider(
                    LocalSettingsPendingAction provides pendingAction.value,
                    LocalSettingsActionConsumed provides { consumedPlain = true },
                    // The user's "Left Backs Out" is on: the wizard must still not page on LEFT.
                    LocalSettingsLeftBacksOut provides true,
                ) {
                    WizardScaffold(
                        stepNumber = 2,
                        title = "Initial Setup",
                        heading = "Choose your controller.",
                        onBack = { backs++ },
                        backEnabled = backEnabled,
                        onSkip = onSkip,
                        confirmLabel = confirmLabel,
                    ) {
                        WizardRow(label = "Continue", sublabel = "Next: ROM folders", onClick = { selects++ })
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun press(action: GamepadAction) {
        actions.trySend(action)
        composeRule.waitUntil(10_000) { consumedPlain }
        consumedPlain = false
        pendingAction.value = null
        composeRule.waitForIdle()
    }

    @Test
    fun `left and right never change the page`() {
        showWizard()

        press(GamepadAction.NAVIGATE_LEFT)
        press(GamepadAction.NAVIGATE_RIGHT)

        assertEquals(0, backs)
        assertEquals(0, skips)
        assertEquals(0, selects)
    }

    @Test
    fun `RB skips to the next page`() {
        showWizard()

        press(GamepadAction.NEXT_CATEGORY)

        assertEquals(1, skips)
        assertEquals(0, selects)
    }

    @Test
    fun `B still steps back a page`() {
        showWizard()

        press(GamepadAction.BACK)

        assertEquals(1, backs)
    }

    @Test
    fun `footer reads Enter, Back, Skip without the old arrow guidance`() {
        showWizard()

        composeRule.onNodeWithText("Enter").assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Skip").assertIsDisplayed()
        assertEquals(
            0,
            composeRule.onAllNodesWithText("Press the", substring = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `a page without Skip shows no Skip prompt and ignores RB`() {
        showWizard(onSkip = null)

        assertEquals(0, composeRule.onAllNodesWithText("Skip").fetchSemanticsNodes().size)
        press(GamepadAction.NEXT_CATEGORY)
        assertEquals(0, skips)
    }

    @Test
    fun `a page can rename the Enter prompt`() {
        showWizard(confirmLabel = "Change")

        composeRule.onNodeWithText("Change").assertIsDisplayed()
    }
}
