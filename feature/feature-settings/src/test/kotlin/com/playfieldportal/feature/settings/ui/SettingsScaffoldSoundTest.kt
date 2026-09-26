package com.playfieldportal.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.sound.LocalMenuSounds
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundSink
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
 * The settings layer's menu-sound contract, driven through the real scaffold with a recording
 * [MenuSoundSink] in place of the app's player.
 *
 * These screens used to be silent: every cue in the app was played from a ViewModel, and settings
 * navigation lives in composition instead. The rules asserted here are the ones the XMB already
 * follows, so a user cannot tell from the sound which layer they are in:
 *
 *  - a move that moves the cursor ticks; a move clamped at a boundary is silent,
 *  - activating something is voiced by the row that owns the action, so SELECT and a tap on the
 *    same row are indistinguishable, and a row with nothing to activate stays silent,
 *  - anything that goes back — B, LEFT-backs-out, leaving slider adjust — plays the back cue,
 *  - an action a screen consumes through onInterceptAction is the screen's to voice, never the
 *    scaffold's (button-remap capture is the case that makes this a rule and not a preference).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class SettingsScaffoldSoundTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val pendingAction = mutableStateOf<GamepadAction?>(null)
    private val actions = Channel<GamepadAction>(Channel.UNLIMITED)
    private var consumedPlain = false

    /** Every cue played, in order. A plain list: written from composition, read from the test. */
    private val played = mutableListOf<MenuSound>()

    private fun showScreen(
        onBack: () -> Unit = {},
        leftBacksOut: Boolean = true,
        onInterceptAction: ((GamepadAction) -> Boolean)? = null,
        body: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            PFPTheme {
                LaunchedEffect(Unit) {
                    for (action in actions) pendingAction.value = action
                }
                // Remembered for the same reason MainActivity remembers the real one: LocalMenuSounds
                // is a static local, so a fresh sink per recomposition would invalidate the subtree.
                val sink = remember { MenuSoundSink { played.add(it) } }
                CompositionLocalProvider(
                    LocalSettingsPendingAction provides pendingAction.value,
                    LocalSettingsActionConsumed provides { consumedPlain = true },
                    LocalSettingsLeftBacksOut provides leftBacksOut,
                    LocalMenuSounds provides sink,
                ) {
                    SettingsScaffold(
                        title = "Settings",
                        subtitle = "Test screen",
                        onBack = onBack,
                        onInterceptAction = onInterceptAction,
                    ) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            body()
                        }
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

    /** Returns everything played since the last call and clears the log. */
    private fun drain(): List<MenuSound> = played.toList().also { played.clear() }

    private fun assertFocusedRow(text: String) {
        composeRule.onNode(isFocused())
            .assert(hasText(text) or hasAnyDescendant(hasText(text)))
    }

    @Test
    fun `vertical movement ticks only when the cursor actually moves`() {
        showScreen {
            SettingsGroup("Appearance")
            SettingsRow(label = "Theme", onClick = {})
            SettingsRow(label = "Wallpaper", onClick = {})
        }

        // Opening the screen is not a press and must not sound.
        assertFocusedRow("Theme")
        assertEquals(emptyList<MenuSound>(), drain())

        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("Wallpaper")
        assertEquals(listOf(MenuSound.SCROLL), drain())

        // Clamped at the last row: the press is swallowed, so the boundary is audible as silence.
        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("Wallpaper")
        assertEquals(emptyList<MenuSound>(), drain())

        press(GamepadAction.NAVIGATE_UP)
        assertFocusedRow("Theme")
        assertEquals(listOf(MenuSound.SCROLL), drain())

        // And clamped at the first row, where UP also pans the column back to the top.
        press(GamepadAction.NAVIGATE_UP)
        assertEquals(emptyList<MenuSound>(), drain())
    }

    @Test
    fun `activation is voiced by the row, so SELECT and a tap sound the same`() {
        var selects = 0
        showScreen {
            SettingsRow(label = "Theme", onClick = { selects++ })
            // Read-only: nothing to activate, so SELECT over it stays silent.
            SettingsValueRow(label = "Version", value = "1.0")
        }

        press(GamepadAction.SELECT)
        assertEquals(1, selects)
        assertEquals(listOf(MenuSound.SELECT), drain())

        press(GamepadAction.NAVIGATE_DOWN)
        assertEquals(listOf(MenuSound.SCROLL), drain())
        press(GamepadAction.SELECT)
        assertEquals(1, selects)
        assertEquals(emptyList<MenuSound>(), drain())

        // Tapping the row plays exactly what SELECT on it played, because both end in the same
        // wrapped lambda. Done last: a tap hands the screen back to touch, and the next controller
        // press is a cursor-revival press the scaffold deliberately consumes without moving.
        composeRule.onNodeWithText("Theme").performClick()
        assertEquals(2, selects)
        assertEquals(listOf(MenuSound.SELECT), drain())
    }

    @Test
    fun `an inline action can opt out of the activation cue`() {
        var previews = 0
        var deletes = 0
        showScreen {
            SettingsRow(
                label = "Boot sound",
                onClick = {},
                actions = listOf(
                    // The Sound screen's Preview: the sample it plays IS the feedback.
                    SettingsRowAction(label = "Preview", onClick = { previews++ }, plays = null) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Preview")
                    },
                    SettingsRowAction(label = "Delete", onClick = { deletes++ }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    },
                ),
            )
        }
        drain()

        // Stepping onto and between inline actions is cursor movement.
        press(GamepadAction.NAVIGATE_RIGHT)
        composeRule.onNode(isFocused()).assert(hasContentDescription("Preview"))
        assertEquals(listOf(MenuSound.SCROLL), drain())

        press(GamepadAction.SELECT)
        assertEquals(1, previews)
        assertEquals(emptyList<MenuSound>(), drain())

        press(GamepadAction.NAVIGATE_RIGHT)
        composeRule.onNode(isFocused()).assert(hasContentDescription("Delete"))
        assertEquals(listOf(MenuSound.SCROLL), drain())

        press(GamepadAction.SELECT)
        assertEquals(1, deletes)
        assertEquals(listOf(MenuSound.SELECT), drain())
    }

    @Test
    fun `everything that goes back plays the back cue`() {
        var backCount = 0
        showScreen(onBack = { backCount++ }) {
            SettingsRow(label = "Theme", onClick = {})
        }
        drain()

        // A row with no inline actions: LEFT leaves the screen, so it is a back and not a move.
        press(GamepadAction.NAVIGATE_LEFT)
        assertEquals(1, backCount)
        assertEquals(listOf(MenuSound.BACK), drain())

        press(GamepadAction.BACK)
        assertEquals(2, backCount)
        assertEquals(listOf(MenuSound.BACK), drain())
    }

    @Test
    fun `with Left Backs Out off LEFT stays the silent no-op it always was`() {
        var backCount = 0
        showScreen(onBack = { backCount++ }, leftBacksOut = false) {
            SettingsRow(label = "Theme", onClick = {})
        }
        drain()

        press(GamepadAction.NAVIGATE_LEFT)
        assertEquals(0, backCount)
        assertEquals(emptyList<MenuSound>(), drain())
    }

    @Test
    fun `slider adjust mode ticks its steps and backs out of itself`() {
        var value = 0.5f
        showScreen {
            SettingsRow(label = "Theme", onClick = {})
            SettingsSliderRow(
                label = "Master volume",
                focusKey = "master",
                value = value,
                onValueChange = { value = it },
                valueRange = 0f..1f,
                steps = 9,
            )
        }
        drain()

        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("Master volume")
        assertEquals(listOf(MenuSound.SCROLL), drain())

        // Entering adjust mode is the slider row's activation.
        press(GamepadAction.SELECT)
        assertEquals(listOf(MenuSound.SELECT), drain())

        // A step along the slider is a cursor move.
        press(GamepadAction.NAVIGATE_RIGHT)
        assertEquals(listOf(MenuSound.SCROLL), drain())
        press(GamepadAction.NAVIGATE_LEFT)
        assertEquals(listOf(MenuSound.SCROLL), drain())

        // BACK leaves adjust mode (it never pops the screen from here), which is a level up.
        press(GamepadAction.BACK)
        assertEquals(listOf(MenuSound.BACK), drain())

        // Still on the screen, so the next BACK is the ordinary one.
        assertFocusedRow("Master volume")
    }

    @Test
    fun `an action the screen consumes is the screen's to voice, not the scaffold's`() {
        var captured = 0
        showScreen(
            // Stands in for ControllerSettingsScreen's remap capture: presses here are being
            // RECORDED, so a cue on each one would sound on input that was never acted on.
            onInterceptAction = { captured++; true },
        ) {
            SettingsRow(label = "Theme", onClick = {})
        }
        drain()

        press(GamepadAction.NAVIGATE_DOWN)
        press(GamepadAction.SELECT)
        press(GamepadAction.BACK)
        assertEquals(3, captured)
        assertEquals(emptyList<MenuSound>(), drain())
    }
}
