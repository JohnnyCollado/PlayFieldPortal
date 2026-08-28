package com.playfieldportal.feature.settings.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.ExperimentalTestApi
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.theme.PFPTheme
import kotlinx.coroutines.channels.Channel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * TEMPORARY visual tour: drives the scaffold with controller actions and saves a PNG at each
 * step to build/test-screenshots so the focus/highlight behaviour can be reviewed visually.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class SettingsScaffoldScreenshotTourTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val pendingAction = mutableStateOf<GamepadAction?>(null)
    private val actions = Channel<GamepadAction>(Channel.UNLIMITED)
    private var consumedPlain = false

    private fun showScreen(body: @Composable () -> Unit) {
        composeRule.setContent {
            PFPTheme {
                LaunchedEffect(Unit) {
                    for (action in actions) pendingAction.value = action
                }
                CompositionLocalProvider(
                    LocalSettingsPendingAction provides pendingAction.value,
                    LocalSettingsActionConsumed provides { consumedPlain = true },
                ) {
                    SettingsScaffold(title = "Settings", subtitle = "Library Manager", onBack = {}) {
                        body()
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

    private fun save(step: String) {
        composeRule.waitForIdle()
        // captureToImage() stalls under this Robolectric setup, so draw the decorated view
        // hierarchy (Compose included) into a bitmap via Robolectric native graphics instead.
        val view = composeRule.activity.window.decorView
        val w = view.width.takeIf { it > 0 } ?: 400
        val h = view.height.takeIf { it > 0 } ?: 800
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File(System.getProperty("user.dir"), "build/test-screenshots").apply { mkdirs() }
        File(dir, "step-$step.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        println("SCREENSHOT saved: ${File(dir, "step-$step.png").absolutePath}")
    }

    @Test
    fun `tour the library manager list with controller actions`() {
        showScreen {
            Column2 {
                SettingsGroup("ROM Root Access")
                SettingsRow(
                    label = "Phone Storage",
                    sublabel = "ROM root",
                    onClick = {},
                    actions = listOf(
                        SettingsRowAction(label = "Replace root folder", onClick = {}) {
                            Icon(Icons.Default.Create, contentDescription = "Replace root folder", tint = SettingsAccent)
                        },
                        SettingsRowAction(label = "Remove root folder", onClick = {}) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove root folder", tint = Color(0xFFE55353))
                        },
                    ),
                )
                SettingsRow(label = "Add ROM Root", sublabel = "Grant a root folder (e.g. /Roms)", onClick = {})
                SettingsGroup("Consoles")
                SettingsRow(label = "PSP Memory Card", sublabel = "/Roms/psp  ·  12 games", onClick = {})
                SettingsRow(label = "SNES Memory Card", sublabel = "/Roms/snes  ·  9 games", onClick = {})
                SettingsGroup("Manage")
                SettingsRow(label = "Add Console", sublabel = "Pick a platform", onClick = {})
                SettingsRow(label = "Scan All Consoles", sublabel = "Configure a ROM folder first")
            }
        }

        // 1. Initial open — first actionable row focused.
        save("01-initial-first-row")

        // 2. DOWN to the next row.
        press(GamepadAction.NAVIGATE_DOWN)
        save("02-add-rom-root")

        // 3. DOWN past the "Consoles" header (skipped) onto the first console card.
        press(GamepadAction.NAVIGATE_DOWN)
        save("03-first-console")

        // 4. DOWN to the read-only row — unified cursor highlight.
        press(GamepadAction.NAVIGATE_DOWN)
        press(GamepadAction.NAVIGATE_DOWN)
        press(GamepadAction.NAVIGATE_DOWN)
        save("04-readonly-scan-all")

        // 5. UP all the way back to the first row, then UP again — clamped + pan to top.
        repeat(5) { press(GamepadAction.NAVIGATE_UP) }
        save("05-clamped-at-top")

        // 6. RIGHT onto the first inline action (Replace icon).
        press(GamepadAction.NAVIGATE_RIGHT)
        save("06-on-replace-icon")

        // 7. RIGHT onto the second inline action (Remove icon).
        press(GamepadAction.NAVIGATE_RIGHT)
        save("07-on-remove-icon")

        // 8. LEFT back to the row.
        press(GamepadAction.NAVIGATE_LEFT)
        save("08-back-on-row")
    }
}

// Small wrapper so the tour content reads like the real Library Manager list.
@Composable
private fun Column2(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        content()
    }
}
