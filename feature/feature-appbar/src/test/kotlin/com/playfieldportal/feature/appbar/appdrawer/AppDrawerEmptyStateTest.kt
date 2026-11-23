package com.playfieldportal.feature.appbar.appdrawer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import com.playfieldportal.core.ui.preview.PfpScreenPreview
import com.playfieldportal.feature.appbar.AppDrawerContent
import com.playfieldportal.feature.appbar.AppDrawerUiState
import com.playfieldportal.feature.appbar.AppFilter
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An empty tab's message sits in the middle of the screen, not the middle of a box that shrank to
 * the message's own width against the left edge (Recently Used without usage access, 23 Nov 2026).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-land-480dpi")
class AppDrawerEmptyStateTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the Usage access message is centred across the screen`() {
        val state = AppDrawerUiState(activeFilter = AppFilter.RECENT, isLoading = false, hasUsageAccess = false)
        composeRule.setContent {
            PfpScreenPreview {
                AppDrawerContent(
                    state = state,
                    searchActive = false,
                    showControllerHint = false,
                    onBack = {}, onSearchQueryChange = {}, onSearchToggle = {}, onSearchDone = {},
                    onFilterSelected = {}, onAppTapped = {}, onAppLaunched = {}, onAppMenu = {},
                    onTouchBrowse = {}, onMenuAction = {}, onCloseMenu = {}, onGrantUsageAccess = {},
                )
            }
        }
        val screen = composeRule.onRoot().getBoundsInRoot()
        val button = composeRule.onNodeWithText("Open Usage Access").getBoundsInRoot()
        val screenMiddle = (screen.left + screen.right) / 2
        val buttonMiddle = (button.left + button.right) / 2
        val off = buttonMiddle - screenMiddle
        assertTrue("button centre is $off from the screen's centre", off.value in -2f..2f)
    }
}
