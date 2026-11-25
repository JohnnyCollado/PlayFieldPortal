package com.playfieldportal.feature.appbar.appdrawer

import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.core.ui.components.PfpConfirmModal
import com.playfieldportal.core.ui.components.PfpModalFocus
import com.playfieldportal.core.ui.detail.PfpBackdropBlurAppliedKey
import com.playfieldportal.core.ui.detail.PfpBackdropHiddenKey
import com.playfieldportal.core.ui.preview.PfpScreenPreview
import com.playfieldportal.feature.appbar.AppDrawerContent
import com.playfieldportal.feature.appbar.AppDrawerUiState
import com.playfieldportal.feature.appbar.AppFilter
import com.playfieldportal.feature.appbar.AppMenuEntry
import com.playfieldportal.feature.appbar.InstalledApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The App Drawer under its options menu (the grid blurs) and under a prompt like Uninstall (the grid
 * hides, the drawer's background stays). The menu and the prompt themselves are never touched.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-land-480dpi")
class AppDrawerBackdropBlurTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app = InstalledApp("pkg.cpuz", "CPU-Z", ColorDrawable(0), isGame = false, isEmulator = false)
    private val idle = AppDrawerUiState(activeFilter = AppFilter.ALL, isLoading = false)

    private fun setUp(state: AppDrawerUiState, prompt: Boolean = false) {
        composeRule.setContent {
            PfpScreenPreview {
                AppDrawerContent(
                    state = state,
                    searchActive = false,
                    showControllerHint = false,
                    onBack = {}, onSearchQueryChange = {}, onSearchToggle = {}, onSearchDone = {},
                    onFilterSelected = {}, onAppTapped = {}, onAppLaunched = {}, onAppMenu = {},
                    onTouchBrowse = {}, onMenuAction = {}, onCloseMenu = {}, onGrantUsageAccess = {},
                    overlay = {
                        Box(Modifier.testTag("overlayProbe"))
                        if (prompt) {
                            PfpConfirmModal(
                                title = "Uninstall CPU-Z?",
                                message = "Android will ask you to confirm.",
                                confirmLabel = "Uninstall",
                                focus = PfpModalFocus.CANCEL,
                                onConfirm = {},
                                onCancel = {},
                            )
                        }
                    },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun layer(key: SemanticsPropertyKey<Boolean>): Boolean =
        composeRule.onNode(SemanticsMatcher.keyIsDefined(key), useUnmergedTree = true).fetchSemanticsNode().config[key]

    private fun SemanticsNode.insideLayer(): Boolean {
        var node: SemanticsNode? = parent
        while (node != null) {
            if (node.config.contains(PfpBackdropBlurAppliedKey)) return true
            node = node.parent
        }
        return false
    }

    @Test
    fun `nothing open, the grid is as it is`() {
        setUp(idle)
        assertFalse(layer(PfpBackdropBlurAppliedKey))
        assertFalse(layer(PfpBackdropHiddenKey))
        assertTrue(composeRule.onNodeWithText("App Drawer").fetchSemanticsNode().insideLayer())
    }

    @Test
    fun `a prompt hides the grid and stays visible itself`() {
        setUp(idle, prompt = true)
        assertTrue(layer(PfpBackdropHiddenKey))
        assertFalse(layer(PfpBackdropBlurAppliedKey))
        assertFalse(composeRule.onNodeWithTag("overlayProbe", useUnmergedTree = true).fetchSemanticsNode().insideLayer())
        assertFalse(composeRule.onNodeWithText("Uninstall CPU-Z?").fetchSemanticsNode().insideLayer())
    }

    @Test
    fun `the options menu blurs the grid but not itself`() {
        val menu = idle.copy(
            menuApp = app,
            menuRows = listOf(AppMenuEntry(id = "uninstall", label = UiText.raw("Uninstall"), isDestructive = true)),
        )
        setUp(menu)
        assertTrue(layer(PfpBackdropBlurAppliedKey))
        assertFalse(layer(PfpBackdropHiddenKey))
        assertFalse(composeRule.onNodeWithText("Uninstall").fetchSemanticsNode().insideLayer())
    }
}
