package com.playfieldportal.feature.settings.ui

import com.playfieldportal.core.ui.components.PfpModalSpec
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.PfpModalTags
import com.playfieldportal.core.ui.theme.PFPTheme
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [rememberSettingsModal] is how every settings screen shows a shared modal: the screen states a
 * spec and plugs three things into its scaffold. These pin what the screens rely on — a confirm
 * that opens on the safe button, a text entry that hands back a trimmed name, a notice either
 * button dismisses, and a fresh cursor every time a modal opens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h468dp")
class SettingsModalSpecTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val pendingAction = mutableStateOf<GamepadAction?>(null)
    private val actions = Channel<GamepadAction>(Channel.UNLIMITED)
    private var consumedPlain = false

    private enum class Open { NONE, DESTRUCTIVE, PLAIN, NOTICE, TEXT }

    private val open = mutableStateOf(Open.NONE)
    private var confirms = 0
    private var cancels = 0
    private var dismissals = 0
    private var confirmedText: String? = null

    private fun showScreen(initially: Open) {
        open.value = initially
        composeRule.setContent {
            PFPTheme {
                LaunchedEffect(Unit) {
                    for (action in actions) pendingAction.value = action
                }
                CompositionLocalProvider(
                    LocalSettingsPendingAction provides pendingAction.value,
                    LocalSettingsActionConsumed provides { consumedPlain = true },
                ) {
                    val close = { open.value = Open.NONE }
                    val modal = rememberSettingsModal(
                        when (open.value) {
                            Open.NONE -> null
                            Open.DESTRUCTIVE, Open.PLAIN -> PfpModalSpec.Confirm(
                                key = open.value,
                                title = "Clear All Logs?",
                                message = "All 3 log files will be deleted.",
                                confirmLabel = "Clear",
                                destructive = open.value == Open.DESTRUCTIVE,
                                onConfirm = { confirms++; close() },
                                onCancel = { cancels++; close() },
                            )
                            Open.NOTICE -> PfpModalSpec.Notice(
                                key = "notice",
                                title = "Couldn't use that sound",
                                message = "That file is not a supported format.",
                                onDismiss = { dismissals++; close() },
                            )
                            Open.TEXT -> PfpModalSpec.TextEntry(
                                key = "rename",
                                title = "Rename Memory Card",
                                initial = "PlayStation",
                                onConfirm = { confirmedText = it; close() },
                                onCancel = { cancels++; close() },
                            )
                        },
                    )
                    SettingsScaffold(
                        title = "Settings",
                        subtitle = "Test screen",
                        onBack = {},
                        modalOpen = modal.open,
                        onInterceptAction = modal.intercept,
                    ) {
                        SettingsRow(label = "Row", onClick = {})
                    }
                    modal.Content()
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
    fun `a destructive confirm opens on cancel, so select backs out`() {
        showScreen(Open.DESTRUCTIVE)

        press(GamepadAction.SELECT)

        assertEquals(0 to 1, confirms to cancels)
        composeRule.onNodeWithTag(PfpModalTags.CARD).assertDoesNotExist()
    }

    @Test
    fun `a plain confirm opens on confirm, so select commits`() {
        showScreen(Open.PLAIN)

        press(GamepadAction.SELECT)

        assertEquals(1 to 0, confirms to cancels)
    }

    @Test
    fun `a notice is dismissed by select`() {
        showScreen(Open.NOTICE)
        composeRule.onNodeWithTag(PfpModalTags.CONFIRM).assertTextEquals("OK")

        press(GamepadAction.SELECT)

        assertEquals(1, dismissals)
        composeRule.onNodeWithTag(PfpModalTags.CARD).assertDoesNotExist()
    }

    @Test
    fun `text entry starts from the initial text and confirms it trimmed`() {
        showScreen(Open.TEXT)
        composeRule.onNodeWithTag(PfpModalTags.FIELD).assertTextEquals("PlayStation")
        composeRule.onNodeWithTag(PfpModalTags.FIELD).performTextReplacement("  PS1  ")

        // Down leaves the field for Save; select commits.
        press(GamepadAction.NAVIGATE_DOWN)
        press(GamepadAction.SELECT)

        assertEquals("PS1", confirmedText)
    }

    @Test
    fun `reopening a modal starts on its initial button again`() {
        showScreen(Open.PLAIN)
        // Move the cursor to Cancel, then cancel from there.
        press(GamepadAction.NAVIGATE_LEFT)
        press(GamepadAction.SELECT)
        assertEquals(0 to 1, confirms to cancels)

        // The same prompt opens again: the cursor must be back on Confirm, not left on Cancel.
        composeRule.runOnUiThread { open.value = Open.PLAIN }
        composeRule.waitForIdle()
        press(GamepadAction.SELECT)

        assertEquals(1 to 1, confirms to cancels)
    }
}
