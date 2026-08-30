package com.playfieldportal.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.theme.PFPTheme
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the real SettingsScaffold with controller actions (the same CompositionLocal channel
 * SettingsNavHost uses) and asserts the ordered focus sequence: section headers are skipped,
 * the cursor walks action row -> read-only row -> text field, SELECT dispatches through
 * ControllerNavigationState, and UP at the first item clamps in place.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class SettingsScaffoldNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val pendingAction = mutableStateOf<GamepadAction?>(null)
    // Actions are pumped through a channel consumed by a composition coroutine: state writes
    // made inside the composition are the only ones the test recomposer observes reliably.
    private val actions = Channel<GamepadAction>(Channel.UNLIMITED)
    // Plain var (not snapshot state): the waitUntil condition reads it from the test thread, and
    // snapshot reads there may not observe composition-side writes.
    private var consumedPlain = false

    private fun showScreen(onBack: () -> Unit = {}, body: @Composable () -> Unit) {
        composeRule.setContent {
            PFPTheme {
                LaunchedEffect(Unit) {
                    for (action in actions) pendingAction.value = action
                }
                CompositionLocalProvider(
                    LocalSettingsPendingAction provides pendingAction.value,
                    LocalSettingsActionConsumed provides { consumedPlain = true },
                ) {
                    SettingsScaffold(
                        title = "Settings",
                        subtitle = "Test screen",
                        onBack = onBack,
                    ) {
                        body()
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Drives one controller action through the scaffold and waits until it is consumed. */
    private fun press(action: GamepadAction) {
        actions.trySend(action)
        composeRule.waitUntil(10_000) { consumedPlain }
        consumedPlain = false
        pendingAction.value = null
        composeRule.waitForIdle()
    }

    /** Asserts the single focused node carries [text] (directly or via a descendant). */
    private fun assertFocusedRow(text: String) {
        composeRule.onNode(isFocused())
            .assert(hasText(text) or hasAnyDescendant(hasText(text)))
    }

    @Test
    fun `controller focus lands on headers, rows and text fields in order`() {
        var themeSelects = 0
        var deleteSelects = 0
        var backCount = 0
        showScreen(onBack = { backCount++ }) {
            SettingsGroup("Appearance")
            SettingsRow(
                label = "Theme",
                onClick = { themeSelects++ },
                actions = listOf(
                    SettingsRowAction(label = "Delete theme", onClick = { deleteSelects++ }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete theme")
                    },
                ),
            )
            SettingsValueRow(label = "Version", value = "1.0")
            SettingsTextFieldRow(label = "Folder", value = "/roms", onValueChange = {})
        }

        // Opens with the first actionable row focused — the header is skipped.
        assertFocusedRow("Theme")

        // UP from the first row clamps in place (headers are skippable, so there is nothing
        // above it) and the screen pans to the top.
        press(GamepadAction.NAVIGATE_UP)
        assertFocusedRow("Theme")

        // DOWN walks action row -> read-only row -> text field, skipping the header.
        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("Version")
        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("/roms")

        // Boundary clamp: DOWN at the last row stays put.
        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("/roms")

        // SELECT dispatches through the model to the focused row's action.
        // From the text field: UP -> read-only row, UP -> Theme action row.
        press(GamepadAction.NAVIGATE_UP)
        press(GamepadAction.NAVIGATE_UP)
        assertFocusedRow("Theme")
        press(GamepadAction.SELECT)
        assertEquals(1, themeSelects)

        // RIGHT reaches the row's inline action; SELECT activates it (not the row action).
        press(GamepadAction.NAVIGATE_RIGHT)
        composeRule.onNode(isFocused()).assert(hasContentDescription("Delete theme"))
        press(GamepadAction.SELECT)
        assertEquals(1, deleteSelects)
        assertEquals(1, themeSelects)

        // LEFT returns to the row.
        press(GamepadAction.NAVIGATE_LEFT)
        assertFocusedRow("Theme")

        // SELECT over a read-only row is a no-op.
        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("Version")
        press(GamepadAction.SELECT)
        assertEquals(1, themeSelects)

        // BACK exits through the scaffold's back handler.
        press(GamepadAction.BACK)
        assertEquals(1, backCount)
    }

    @Test
    fun `rows inserted mid-list keep visual navigation order`() {
        // Mimics a fresh Library Manager open: placeholder rows compose first, then the ROM
        // root path and console cards load in and insert mid-list (async data).
        val roots = mutableStateOf(emptyList<String>())
        val consoles = mutableStateOf(emptyList<String>())
        val load = Channel<Unit>(Channel.UNLIMITED)

        composeRule.setContent {
            PFPTheme {
                LaunchedEffect(Unit) {
                    for (action in actions) pendingAction.value = action
                }
                LaunchedEffect(Unit) {
                    for (u in load) {
                        roots.value = listOf("Phone Storage")
                        consoles.value = listOf("PSP Memory Card", "SNES Memory Card")
                    }
                }
                CompositionLocalProvider(
                    LocalSettingsPendingAction provides pendingAction.value,
                    LocalSettingsActionConsumed provides { consumedPlain = true },
                ) {
                    SettingsScaffold(title = "Settings", subtitle = "Library Manager", onBack = {}) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            SettingsGroup("ROM Root Access")
                            if (roots.value.isEmpty()) {
                                SettingsRow(label = "No ROM roots configured", sublabel = "Add a folder below")
                            } else {
                                roots.value.forEach { SettingsRow(label = it, onClick = {}) }
                            }
                            SettingsRow(label = "Add ROM Root", sublabel = "Grant a root folder", onClick = {})
                            SettingsGroup("Consoles")
                            if (consoles.value.isEmpty()) {
                                Text(
                                    text = "No consoles configured",
                                    color = SettingsSubtext,
                                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
                                )
                            } else {
                                consoles.value.forEach { SettingsRow(label = it, sublabel = "console", onClick = {}) }
                            }
                            SettingsGroup("Manage")
                            SettingsRow(label = "Add Console", onClick = {})
                            SettingsRow(label = "Set Up ROM Folders", onClick = {})
                            SettingsRow(label = "Scan All Consoles", sublabel = "Configure a ROM folder first")
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // Fresh open: Add ROM Root is the first actionable row.
        assertFocusedRow("Add ROM Root")

        // The root path and console cards load in, inserting rows mid-list.
        load.trySend(Unit)
        composeRule.waitForIdle()

        // DOWN walks the VISUAL order — the inserted rows sit where they belong, so the cursor
        // goes Add ROM Root -> PSP -> SNES -> Add Console (the Manage rows come after the
        // consoles), instead of the stale registration order (Add ROM Root -> Add Console).
        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("PSP Memory Card")
        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("SNES Memory Card")
        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("Add Console")
        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("Set Up ROM Folders")
        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("Scan All Consoles")
        // Clamped at the last row.
        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("Scan All Consoles")

        // And UP walks back up through the inserted rows to the root path at the top.
        press(GamepadAction.NAVIGATE_UP)
        assertFocusedRow("Set Up ROM Folders")
        press(GamepadAction.NAVIGATE_UP)
        assertFocusedRow("Add Console")
        press(GamepadAction.NAVIGATE_UP)
        assertFocusedRow("SNES Memory Card")
        press(GamepadAction.NAVIGATE_UP)
        assertFocusedRow("PSP Memory Card")
        press(GamepadAction.NAVIGATE_UP)
        assertFocusedRow("Add ROM Root")
        press(GamepadAction.NAVIGATE_UP)
        assertFocusedRow("Phone Storage")
        // Clamped at the top row.
        press(GamepadAction.NAVIGATE_UP)
        assertFocusedRow("Phone Storage")
    }

    @Test
    fun `first dpad press after a touch drag re-anchors to the row nearest the viewport centre`() {
        // A long, scrollable list so the viewport centre lands mid-list — not on the stale
        // pre-drag row at the top.
        showScreen(onBack = {}) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                repeat(30) { i -> SettingsRow(label = "Row ${i + 1}", onClick = {}) }
            }
        }
        // Opens focused on the first row.
        assertFocusedRow("Row 1")

        // Measure the geometry the scaffold re-anchors against: the content top is the root Y
        // of the first row (the scrollable column starts at the content box), and the viewport
        // centre is contentTop + half the configured screen height (the same LocalConfiguration
        // screenHeightDp the scaffold reads — not the inset-reduced root bounds).
        val density = composeRule.density.density
        val screenHeightDp = composeRule.activity.resources.configuration.screenHeightDp.toFloat()
        val screenHeightPx = screenHeightDp * density
        val firstRowNode = composeRule.onNode(isFocused()).fetchSemanticsNode()
        val contentTop = firstRowNode.boundsInRoot.top
        val rowHeight = firstRowNode.boundsInRoot.height
        val viewportCenter = contentTop + screenHeightPx / 2f

        // A real touch drag: the contact itself flags the screen as touch-scrolled (the scaffold
        // hides the cursor on any pointer activity); the small move keeps the list from scrolling.
        composeRule.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(0f, -5f))
            up()
        }
        composeRule.waitForIdle()

        // Row centres sit half a row below their tops, so compare against centre + rowHeight/2
        // (the scaffold compares row tops against the viewport centre).
        val nearest: Int = (1..30).minByOrNull { i: Int ->
            val centerY = composeRule.onNodeWithText("Row $i")
                .fetchSemanticsNode().boundsInRoot.center.y
            abs(centerY - (viewportCenter + rowHeight / 2f))
        }!!

        // First DOWN: re-anchor to the visible centre, then step one row below it — never the
        // stale pre-drag row (Row 1) and its continuation (Row 2).
        press(GamepadAction.NAVIGATE_DOWN)
        assertFocusedRow("Row ${(nearest + 1).coerceAtMost(30)}")
    }
}
