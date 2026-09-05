package com.playfieldportal.feature.appbar.appdrawer

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
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

/**
 * Three-rows guarantee on a small viewport (Robolectric JVM Compose test — the same pattern as
 * feature-settings): render the real [AppDrawerContent] with 20 apps on a w480dp-h640dp screen and
 * assert the third row of tiles fits fully above the permanent footer slot. Guards the
 * [adaptiveArtworkSize] contract: nothing clipped, nothing hidden under the footer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w480dp-h640dp")
class AppDrawerThreeRowsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `three full tile rows fit above the footer on a small screen`() {
        // 20 tiles at 6 columns = 4 rows — more content than the 3 rows the viewport must show,
        // so the grid has something to scroll and the assertions are meaningful.
        val apps = (1..20).map { i ->
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
            activeFilter = AppFilter.ALL,
            isLoading = false,
            selectedIndex = 0,
        )

        composeRule.setContent {
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
                    onConfirmUninstall = {},
                    onCancelUninstall = {},
                    onGrantUsageAccess = {},
                )
            }
        }
        composeRule.waitForIdle()

        // The drawer's LazyVerticalGrid is the only vertically scrollable node in the content.
        val gridNode: SemanticsNodeInteraction = composeRule.onNode(hasVerticalScrollAction())
        val gridBottom = gridNode.fetchSemanticsNode().boundsInRoot.bottom

        // Every composed tile must sit fully inside the grid viewport: a bottom edge past the
        // grid's bottom means a row is clipped under the footer slot. Tile icons carry the app
        // label as contentDescription, so "App <n>" matches each tile.
        val visibleTiles = composeRule
            .onAllNodesWithContentDescription("App ", substring = true)
            .fetchSemanticsNodes()
        assert(visibleTiles.isNotEmpty()) { "expected the grid to have visible tiles" }
        visibleTiles.forEach { node ->
            val bottom = node.boundsInRoot.bottom
            assert(bottom <= gridBottom + 0.5f) {
                "tile bottom $bottom extends past grid viewport bottom $gridBottom — clipped under the footer"
            }
        }

        // The third row must actually be composed and fully visible: with the 3-row sizing
        // guarantee, tile 18 (row 3, last column) is on screen with its label above the footer.
        val thirdRowTile = composeRule
            .onAllNodesWithContentDescription("App 18", substring = true)
            .fetchSemanticsNodes()
        assert(thirdRowTile.isNotEmpty()) {
            "third-row tile not composed — fewer than three rows visible in the viewport"
        }
        val thirdBottom = thirdRowTile[0].boundsInRoot.bottom
        assert(thirdBottom <= gridBottom) {
            "third-row tile bottom $thirdBottom extends past grid viewport bottom $gridBottom"
        }

        // Footer-slot invariant: the alpha-reserved hint bar (invisible here) still reserves its
        // height, so the grid may not run to the root bottom edge — there must be real clearance
        // equal to at least the footer's own vertical padding.
        val rootBottom = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        val minFooterSlot = with(composeRule.density) { 24.dp.toPx() } // pill padding (12dp × 2)
        assert(gridBottom <= rootBottom - minFooterSlot) {
            "grid viewport bottom $gridBottom runs into the footer slot (root bottom $rootBottom)"
        }
    }

    /** The drawer's LazyVerticalGrid: the only node carrying the lazy-grid scroll-to-index action. */
    private fun hasVerticalScrollAction() = SemanticsMatcher("has scroll-to-index action") { node ->
        node.config.contains(SemanticsActions.ScrollToIndex)
    }
}
