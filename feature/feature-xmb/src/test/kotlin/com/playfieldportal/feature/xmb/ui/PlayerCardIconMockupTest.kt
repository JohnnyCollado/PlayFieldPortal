package com.playfieldportal.feature.xmb.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import com.playfieldportal.core.domain.model.TouchNavButtonMode
import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.core.ui.preview.PfpScreenPreview
import com.playfieldportal.core.ui.wave.WaveStyle
import com.playfieldportal.feature.xmb.viewmodel.XMBItem
import com.playfieldportal.feature.xmb.viewmodel.XMBUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A picture of the Shiba Coins column with the player card focused, drawn by the real XMB shell:
 * the Shiba's head as the player's icon, and the title line reading Title · Level · Bones.
 *
 *     ./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*PlayerCardIconMockupTest*"
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "w640dp-h360dp-land-480dpi")
class PlayerCardIconMockupTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun `player card`() = shoot()

    private fun shoot() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalSystemStatusOverride provides SystemStatus(wifiLevel = 4, controllerConnected = true),
            ) {
                PfpScreenPreview { XMBShell(uiState = state) }
            }
        }
        // A device draws every frame; here one is only drawn when asked for, and the focus glow is
        // built from a picture taken a moment after the first.
        val view = composeRule.activity.window.decorView
        repeat(8) {
            view.draw(android.graphics.Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
            composeRule.mainClock.advanceTimeBy(300)
            Thread.sleep(60)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File(System.getProperty("user.dir"), "build/test-screenshots/mockups").apply { mkdirs() }
        val file = File(dir, "player-card.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT saved: ${file.absolutePath} ${view.width}x${view.height}")
    }

    private fun category(id: String, name: String, position: Int) =
        Category(id = id, name = name, iconKey = id, type = CategoryType.BUILT_IN, position = position)

    private val state = XMBUiState(
        categories = listOf(
            category(BuiltInCategory.GAMES, "Game", 0),
            category(BuiltInCategory.ACHIEVEMENTS, "Shiba Coins", 1),
            category(BuiltInCategory.ANDROID, "Network", 2),
        ),
        selectedCategoryIndex = 1,
        currentItems = listOf(
            XMBItem(
                id = "ach_summary_mock",
                titleText = UiText.raw("Top Dog"),
                subtitleText = UiText.raw("12,480 of 31,200 coins · 86 games tracked · 9 mastered"),
                levelBadge = UiText.raw("Lv 27"),
                boneCount = 2,
            ),
            XMBItem(id = "ach_all", title = "All Tracked Games", subtitle = "86 games"),
            XMBItem(id = "ach_untracked", title = "Untracked", subtitle = "14 games"),
        ),
        selectedItemIndex = 0,
        showBootSequence = false,
        startupPermissionsSettled = true,
        initialSetupDecided = true,
        waveStyle = WaveStyle.STATIC,
        touchNavButtonMode = TouchNavButtonMode.ALWAYS_HIDE,
        lastInputWasTouch = false,
    )
}
