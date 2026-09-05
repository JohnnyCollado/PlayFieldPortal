package com.playfieldportal.feature.xmb.ui.apppicker

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.ui.preview.PfpScreenPreview
import com.playfieldportal.feature.xmb.viewmodel.AppPickerEntry
import com.playfieldportal.feature.xmb.viewmodel.AppPickerState
import com.playfieldportal.feature.xmb.viewmodel.AppPickerTarget
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Three-rows guarantee on a small viewport (Robolectric JVM Compose test — the same pattern as
 * feature-settings): render the real [AppPickerScreen] with 28 apps on a w480dp-h640dp screen and
 * assert the third row of tiles fits fully above the permanent footer. Guards the
 * [pickerAdaptiveArtworkSize] contract: nothing clipped, nothing hidden under the footer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w480dp-h640dp")
class AppPickerThreeRowsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `three full tile rows fit above the footer on a small screen`() {
        // 28 tiles at 7 columns = 4 rows — more content than the 3 rows the viewport must show,
        // so the grid has something to scroll and the assertions are meaningful.
        val apps = (1..28).map { i ->
            AppPickerEntry(
                packageName = "com.test.app$i",
                label = "App $i",
                icon = android.graphics.drawable.ColorDrawable(
                    if (i % 2 == 0) android.graphics.Color.GRAY else android.graphics.Color.DKGRAY,
                ),
            )
        }
        val state = AppPickerState(
            title = "Add Apps",
            target = AppPickerTarget.AndroidGames(platformId = "android"),
            apps = apps,
        )

        composeRule.setContent {
            PfpScreenPreview {
                AppPickerScreen(
                    state = state,
                    onTileTapped = {},
                    onTouchBrowse = {},
                    onHeaderBack = {},
                    onSearchToggle = {},
                    onSearchChange = {},
                    onSearchDone = {},
                    onApply = {},
                    onConfirmRemoval = {},
                    onCancelRemoval = {},
                )
            }
        }
        composeRule.waitForIdle()

        // The picker's LazyVerticalGrid is the only vertically scrollable node in the content.
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
        // guarantee, tile 21 (row 3, last column) is on screen with its label above the footer.
        val thirdRowTile = composeRule
            .onAllNodesWithContentDescription("App 21", substring = true)
            .fetchSemanticsNodes()
        assert(thirdRowTile.isNotEmpty()) {
            "third-row tile not composed — fewer than three rows visible in the viewport"
        }
        val thirdBottom = thirdRowTile[0].boundsInRoot.bottom
        assert(thirdBottom <= gridBottom) {
            "third-row tile bottom $thirdBottom extends past grid viewport bottom $gridBottom"
        }

        // Footer-slot invariant: the permanent footer (prompt bar + its vertical padding) lives
        // below the grid, so the grid may not run to the root bottom edge — there must be real
        // clearance equal to at least the footer's own vertical padding.
        val rootBottom = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        val minFooterSlot = with(composeRule.density) { 24.dp.toPx() } // footer padding (12dp × 2)
        assert(gridBottom <= rootBottom - minFooterSlot) {
            "grid viewport bottom $gridBottom runs into the footer slot (root bottom $rootBottom)"
        }
    }

    /** The picker's LazyVerticalGrid: the only node carrying the lazy-grid scroll-to-index action. */
    private fun hasVerticalScrollAction() = SemanticsMatcher("has scroll-to-index action") { node ->
        node.config.contains(SemanticsActions.ScrollToIndex)
    }
}
