package com.playfieldportal.feature.xmb.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import com.playfieldportal.core.domain.model.TouchNavButtonMode
import com.playfieldportal.core.ui.preview.PfpScreenPreview
import com.playfieldportal.core.ui.wave.WaveStyle
import com.playfieldportal.feature.xmb.ui.apppicker.AppPickerScreen
import com.playfieldportal.feature.xmb.viewmodel.AppPickerEntry
import com.playfieldportal.feature.xmb.viewmodel.AppPickerState
import com.playfieldportal.feature.xmb.viewmodel.AppPickerTarget
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
 * Layout review across screen formats PFP has no device for: renders the XMB and the App Picker at
 * each format's landscape size, in controller and in touch mode, and saves PNGs to
 * `feature/feature-xmb/build/test-screenshots/devices/<format>-<screen>-<family>.png`.
 *
 * A visual check, not a pixel diff — nothing is asserted beyond rendering. Sizes are each device's
 * default display scaling (a user's Display Size setting moves them). SDK 32 takes the wave's
 * pre-AGSL path, which Robolectric can draw; One UI cutouts, taskbars and fold posture are not
 * modelled. Run:
 *
 *     ./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*XmbDeviceFormatScreenshotTest*"
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32])
class XmbDeviceFormatScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // ── Formats (landscape) ───────────────────────────────────────────────────

    @Config(qualifiers = "w792dp-h360dp-land-480dpi")
    @Test fun `oneplus baseline`() = shoot("oneplus")

    @Config(qualifiers = "w832dp-h384dp-land-450dpi")
    @Test fun `galaxy s26`() = shoot("galaxy-s26")

    @Config(qualifiers = "w891dp-h412dp-land-420dpi")
    @Test fun `galaxy s26 ultra`() = shoot("galaxy-s26-ultra")

    @Config(qualifiers = "w960dp-h411dp-land-420dpi")
    @Test fun `galaxy z fold 7 cover`() = shoot("fold7-cover")

    // Nearly square: the format most likely to break a layout tuned for wide phones.
    @Config(qualifiers = "w832dp-h750dp-land-420dpi")
    @Test fun `galaxy z fold 7 inner`() = shoot("fold7-inner")

    @Config(qualifiers = "w960dp-h411dp-land-420dpi")
    @Test fun `galaxy z flip 7 inner`() = shoot("flip7-inner")

    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    @Test fun `11 inch tablet`() = shoot("tablet-11in")

    // ── Rendering ─────────────────────────────────────────────────────────────

    private val touch = mutableStateOf(false)
    private val screen = mutableStateOf(Screen.XMB)

    private enum class Screen { XMB, APP_PICKER }

    private fun shoot(format: String) {
        // Below SDK 33 ContextCompat.registerReceiver (the status strip's battery receiver) checks
        // the app's own NOT_EXPORTED permission, which only an app manifest declares. Grant it here.
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        // The wave and the row springs never go idle on their own; drive the clock by hand.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            PfpScreenPreview {
                when (screen.value) {
                    Screen.XMB -> XMBShell(uiState = xmbState(touch.value))
                    Screen.APP_PICKER -> AppPickerScreen(
                        state = appPickerState,
                        onTileTapped = {},
                        onTouchBrowse = {},
                        onHeaderBack = {},
                        onSearchToggle = {},
                        onSearchChange = {},
                        onSearchDone = {},
                        onApply = {},
                        onConfirmRemoval = {},
                        onCancelRemoval = {},
                        showTouchControls = touch.value,
                    )
                }
            }
        }
        for (s in Screen.entries) {
            for (t in listOf(false, true)) {
                screen.value = s
                touch.value = t
                save("$format-${s.name.lowercase().replace('_', '-')}-${if (t) "touch" else "pad"}")
            }
        }
    }

    private fun save(name: String) {
        composeRule.mainClock.advanceTimeBy(SETTLE_MS)
        // captureToImage() stalls under Robolectric here (see SettingsScaffoldScreenshotTourTest);
        // draw the decor view through native graphics instead.
        val view = composeRule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File(System.getProperty("user.dir"), "build/test-screenshots/devices").apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT saved: ${file.absolutePath}")
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun category(id: String, name: String, position: Int) =
        Category(id = id, name = name, iconKey = id, type = CategoryType.BUILT_IN, position = position)

    private val categories = listOf(
        category(BuiltInCategory.SETTINGS, "Settings", 0),
        category(BuiltInCategory.PHOTO, "Photo", 1),
        category(BuiltInCategory.MUSIC, "Music", 2),
        category(BuiltInCategory.VIDEO, "Video", 3),
        category(BuiltInCategory.GAMES, "Game", 4),
        category(BuiltInCategory.ANDROID, "Network", 5),
    )

    private val items = listOf(
        "Chrome", "Firefox", "Discord", "Steam Link", "Moonlight", "YouTube", "Twitch", "Spotify",
    ).mapIndexed { i, title -> XMBItem(id = "app$i", title = title, subtitle = if (i == 1) "Browser" else null) }

    private fun xmbState(touch: Boolean) = XMBUiState(
        categories = categories,
        selectedCategoryIndex = 5,
        currentItems = items,
        selectedItemIndex = 1,
        showBootSequence = false,
        startupPermissionsSettled = true,
        initialSetupDecided = true,
        waveStyle = WaveStyle.STATIC,
        touchNavButtonMode = if (touch) TouchNavButtonMode.ALWAYS_SHOW else TouchNavButtonMode.ALWAYS_HIDE,
        lastInputWasTouch = touch,
    )

    private val appPickerState = AppPickerState(
        title = "Add Apps",
        target = AppPickerTarget.AndroidGames(platformId = "android"),
        apps = (1..28).map { i ->
            AppPickerEntry(
                packageName = "com.test.app$i",
                label = "App $i",
                icon = android.graphics.drawable.ColorDrawable(
                    if (i % 2 == 0) android.graphics.Color.GRAY else android.graphics.Color.DKGRAY,
                ),
            )
        },
    )

    private companion object {
        const val SETTLE_MS = 2_000L
    }
}
