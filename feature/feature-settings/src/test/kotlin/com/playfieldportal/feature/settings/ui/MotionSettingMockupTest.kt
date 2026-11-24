package com.playfieldportal.feature.settings.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.playfieldportal.core.ui.theme.PFPTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * MOCKUP, not a feature: the top of Settings ▸ Interface ▸ Display with the proposed single Motion
 * setting, built from the real settings scaffold and rows. The wording is the proposal's; nothing
 * here is wired to a preference. Two pictures: Motion on Reduced (today's default behaviour) and on
 * Static, where the rows it overrides say so.
 *
 *     ./gradlew :feature:feature-settings:testDebugUnitTest -Pmockups --tests "*MotionSettingMockupTest*"
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-land-480dpi")
class MotionSettingMockupTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `motion reduced`() = shoot(
        name = "motion-setting-reduced",
        motion = "Reduced",
        motionSub = "Only what is selected moves: its artwork, its video snap and its glow. The background keeps moving.",
        heldStill = false,
    )

    @Test
    fun `motion static`() = shoot(
        name = "motion-setting-static",
        motion = "Static",
        motionSub = "Nothing moves on its own. Menus still slide when you move through them.",
        heldStill = true,
    )

    private fun shoot(name: String, motion: String, motionSub: String, heldStill: Boolean) {
        composeRule.setContent {
            PFPTheme {
                CompositionLocalProvider(
                    LocalSettingsPendingAction provides null,
                    LocalSettingsActionConsumed provides { },
                ) {
                    SettingsScaffold(title = "Settings", subtitle = "Display", onBack = {}) {
                        androidx.compose.foundation.layout.Column(
                            androidx.compose.ui.Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        ) {
                        SettingsGroup("Motion")
                        SettingsValueRow(label = "Motion", value = motion, sublabel = motionSub, onClick = {}, opensPicker = true)
                        SettingsGroup("Appearance")
                        SettingsValueRow(
                            label = "Wave style",
                            value = "Animated",
                            sublabel = if (heldStill) "Held still while Motion is Static." else "How lively the background wave is.",
                            onClick = {},
                            opensPicker = true,
                        )
                        SettingsValueRow(
                            label = "Focus glow",
                            value = "Pulsing",
                            sublabel = if (heldStill) "Held steady while Motion is Static." else "The light round the selected icon slowly swells and fades.",
                            onClick = {},
                        )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val view = composeRule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File(System.getProperty("user.dir"), "build/test-screenshots/mockups").apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT saved: ${file.absolutePath} ${view.width}x${view.height}")
    }
}
