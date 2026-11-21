package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.core.domain.model.resolve
import com.playfieldportal.feature.xmb.viewmodel.toPFPColors
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
 * Pictures of the status strip, to look at: the real XMB shell at the Odin's size, under a
 * controller (plain readouts) and under touch (the filter and bell as raised pills), and the touch
 * strip in four colour schemes. The strip is packed to the end edge, one tone throughout.
 *
 *     ./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*StatusStripAnchorMockupTest*"
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "w640dp-h360dp-land-480dpi")
class StatusStripAnchorMockupTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `controller`() = shoot(touch = false, name = "status-strip-pad")

    @Test
    fun `touch`() = shoot(touch = true, name = "status-strip-touch")

    /** The proposal under four of the app's own colour schemes: the bubble takes each one's colours. */
    @Test fun `scheme classic blue`() = scheme(com.playfieldportal.core.domain.model.XmbColorScheme.CLASSIC_BLUE)
    @Test fun `scheme crimson red`() = scheme(com.playfieldportal.core.domain.model.XmbColorScheme.CRIMSON_RED)
    @Test fun `scheme fresh green`() = scheme(com.playfieldportal.core.domain.model.XmbColorScheme.FRESH_GREEN)
    @Test fun `scheme charcoal`() = scheme(com.playfieldportal.core.domain.model.XmbColorScheme.CHARCOAL)

    /** The touch strip over a wallpaper (a clean crop of the owner's own), in two schemes. */
    @Test fun `wallpaper classic blue`() = wallpaper(com.playfieldportal.core.domain.model.XmbColorScheme.CLASSIC_BLUE)
    @Test fun `wallpaper crimson red`() = wallpaper(com.playfieldportal.core.domain.model.XmbColorScheme.CRIMSON_RED)

    private fun wallpaper(scheme: com.playfieldportal.core.domain.model.XmbColorScheme) {
        val file = File(System.getProperty("user.dir"), "../../docs/mockups/wallpaper-sample.png")
        org.junit.Assume.assumeTrue(file.exists())
        wallpaperPath = file.canonicalPath
        shoot(touch = true, name = "status-strip-wallpaper-${scheme.name.lowercase()}", colors = scheme.resolve(1).toPFPColors())
    }

    private var wallpaperPath: String? = null

    private fun scheme(scheme: com.playfieldportal.core.domain.model.XmbColorScheme) = shoot(
        touch = true,
        name = "status-strip-scheme-${scheme.name.lowercase()}",
        colors = scheme.resolve(1).toPFPColors(),
    )

    private val shown = androidx.compose.runtime.mutableStateOf<com.playfieldportal.core.ui.theme.PFPColors?>(null)

    private fun shoot(
        touch: Boolean,
        name: String,
        colors: com.playfieldportal.core.ui.theme.PFPColors = com.playfieldportal.core.ui.theme.DefaultPFPColors,
    ) {
        shown.value = colors
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        // A battery reading, so the percentage shows as it does on a device.
        shadowOf(app.getSystemService(android.os.BatteryManager::class.java))
            .setIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY, 79)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(
                // What the Odin showed in the owner's screenshot: a pad and Wi-Fi, no Bluetooth icon.
                LocalSystemStatusOverride provides SystemStatus(wifiLevel = 4, controllerConnected = true),
            ) {
                PfpScreenPreview {
                    XMBShell(
                        uiState = state.copy(
                            touchNavButtonMode = if (touch) TouchNavButtonMode.ALWAYS_SHOW else TouchNavButtonMode.ALWAYS_HIDE,
                            lastInputWasTouch = touch,
                            unreadNotifications = 2,
                            themeColors = shown.value ?: colors,
                            customWallpaperPath = wallpaperPath,
                        ),
                    )
                }
            }
        }
        save(name)
    }

    private fun save(name: String) {
        composeRule.mainClock.advanceTimeBy(1_500)
        val view = composeRule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File(System.getProperty("user.dir"), "build/test-screenshots/mockups").apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT saved: ${file.absolutePath} ${view.width}x${view.height}")
    }

    private fun category(id: String, name: String, position: Int) =
        Category(id = id, name = name, iconKey = id, type = CategoryType.BUILT_IN, position = position)

    private val state = XMBUiState(
        categories = listOf(
            category(BuiltInCategory.SETTINGS, "Settings", 0),
            category(BuiltInCategory.PHOTO, "Photo", 1),
            category(BuiltInCategory.MUSIC, "Music", 2),
            category(BuiltInCategory.VIDEO, "Video", 3),
            category(BuiltInCategory.GAMES, "Game", 4),
            category(BuiltInCategory.ANDROID, "Network", 5),
        ),
        selectedCategoryIndex = 4,
        currentItems = listOf("All Games", "Game Boy Advance", "PlayStation Portable", "Favorites")
            .mapIndexed { i, t -> XMBItem(id = "row$i", title = t) },
        selectedItemIndex = 0,
        sortLabel = UiText.raw("Filter: Custom"),
        showBootSequence = false,
        startupPermissionsSettled = true,
        initialSetupDecided = true,
        waveStyle = WaveStyle.STATIC,
        touchNavButtonMode = TouchNavButtonMode.ALWAYS_HIDE,
        lastInputWasTouch = false,
    )
}
