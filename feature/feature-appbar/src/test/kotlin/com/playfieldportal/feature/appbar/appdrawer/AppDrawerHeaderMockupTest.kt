package com.playfieldportal.feature.appbar.appdrawer

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.playfieldportal.core.ui.preview.PfpScreenPreview
import com.playfieldportal.feature.appbar.AppDrawerContent
import com.playfieldportal.feature.appbar.AppDrawerUiState
import com.playfieldportal.feature.appbar.AppFilter
import com.playfieldportal.feature.appbar.InstalledApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A picture of the real App Drawer at the Odin's size, to look at its header: the shared breadcrumb.
 *
 *     ./gradlew :feature:feature-appbar:testDebugUnitTest -Pmockups --tests "*AppDrawerHeaderMockupTest*"
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-land-480dpi")
class AppDrawerHeaderMockupTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun `all apps`() = shoot("app-drawer-header", AppFilter.ALL)

    private fun shoot(name: String, filter: AppFilter) {
        val apps = (1..18).map { i ->
            InstalledApp(
                packageName = "com.test.app$i",
                label = "App $i",
                icon = android.graphics.drawable.ColorDrawable(
                    if (i % 2 == 0) android.graphics.Color.GRAY else android.graphics.Color.DKGRAY,
                ),
                isGame = false,
                isEmulator = false,
            )
        }
        val state = AppDrawerUiState(
            allApps = apps,
            visibleApps = apps,
            activeFilter = filter,
            isLoading = false,
            selectedIndex = 0,
            filterCounts = mapOf(AppFilter.ALL to 79, AppFilter.GAMES to 34, AppFilter.EMULATORS to 20, AppFilter.RECENT to 25),
        )
        composeRule.setContent {
            run {
                PfpScreenPreview {
                    AppDrawerContent(
                        state = state,
                        searchActive = false,
                        showControllerHint = false,
                        onBack = {},
                        onSearchQueryChange = {},
                        onSearchToggle = {},
                        onSearchDone = {},
                        onFilterSelected = {},
                        onAppTapped = {},
                        onAppLaunched = {},
                        onAppMenu = {},
                        onTouchBrowse = {},
                        onMenuAction = {},
                        onCloseMenu = {},
                        onGrantUsageAccess = {},
                    )
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
