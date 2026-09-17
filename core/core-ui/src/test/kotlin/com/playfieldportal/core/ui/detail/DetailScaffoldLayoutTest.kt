package com.playfieldportal.core.ui.detail

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.preview.PfpScreenPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The detail frame's layout contract, on a real composition (Robolectric JVM Compose test — the
 * same pattern as feature-xmb's AppPickerThreeRowsTest).
 *
 * The design requires the helper footer to be a *permanent layout row* rather than an overlay, and
 * requires fading its hints never to move the body. Both are geometry facts about a composed tree,
 * so nothing short of laying one out can check them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w480dp-h640dp")
class DetailScaffoldLayoutTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** The viewport the page body is allowed to use: everything above the footer. */
    private fun bodyViewportBottom(): Float =
        composeRule.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot.bottom

    private fun footerBounds() =
        composeRule.onNodeWithTag("footer").fetchSemanticsNode().boundsInRoot

    private fun rootHeight(): Float =
        composeRule.onNodeWithTag("page").fetchSemanticsNode().boundsInRoot.height

    @Test
    fun `the footer is a reserved layout row the body never scrolls under`() {
        var hintsVisible by mutableStateOf(true)
        composeRule.setContent {
            PfpScreenPreview {
                PfpDetailScaffold(
                    modifier = Modifier.testTag("page"),
                    scrollState = rememberScrollState(),
                    header = {
                        PfpDetailBreadcrumb(
                            title = "PlayStation",
                            subtitle = "ROM",
                            onBack = {},
                        )
                    },
                    footer = {
                        PfpDetailHelperFooter(
                            items = listOf(
                                ControllerPromptItem(GamepadAction.SELECT, "Select"),
                                ControllerPromptItem(GamepadAction.BACK, "Back"),
                            ),
                            visible = hintsVisible,
                            modifier = Modifier.testTag("footer"),
                        )
                    },
                ) {
                    // Far taller than the viewport, so the body really does scroll.
                    Column(Modifier.fillMaxWidth().height(2400.dp).background(Color.DarkGray)) {}
                }
            }
        }
        composeRule.waitForIdle()

        val footer = footerBounds()
        val expectedFooterHeight = with(composeRule.density) { DetailFooterHeight.toPx() }
        assertEquals(
            "the footer must reserve exactly DetailFooterHeight",
            expectedFooterHeight,
            footer.height,
            1f,
        )
        assertEquals(
            "the footer must be pinned to the bottom of the page",
            rootHeight(),
            footer.bottom,
            1f,
        )
        assertTrue(
            "the body viewport (${bodyViewportBottom()}) must end at the footer top (${footer.top})",
            bodyViewportBottom() <= footer.top + 0.5f,
        )

        // Fading the prompts (touch input) must not move anything: same reserved height, same body.
        val viewportBefore = bodyViewportBottom()
        composeRule.runOnIdle { hintsVisible = false }
        composeRule.waitForIdle()

        assertEquals("a faded footer keeps its reserved height", expectedFooterHeight, footerBounds().height, 1f)
        assertEquals("a faded footer must not resize the body", viewportBefore, bodyViewportBottom(), 0.5f)
    }

    @Test
    fun `the breadcrumb shows the platform over the entry kind and always offers a touch way back`() {
        var backs = 0
        composeRule.setContent {
            PfpScreenPreview {
                PfpDetailScaffold(
                    modifier = Modifier.testTag("page"),
                    header = {
                        PfpDetailBreadcrumb(
                            title = "Nintendo DS",
                            subtitle = "ROM",
                            onBack = { backs++ },
                        )
                    },
                ) {
                    Column(Modifier.fillMaxWidth().height(40.dp)) {}
                }
            }
        }
        composeRule.waitForIdle()

        // The ◀ title / subtitle header, with the platform keeping its own casing.
        composeRule.onNodeWithText("◀").assertExists()
        composeRule.onNodeWithText("Nintendo DS").assertExists()
        composeRule.onNodeWithText("ROM").assertExists()
        // Unmerged: the clickable back target merges both texts into one node with one set of bounds.
        val title = composeRule.onNodeWithText("Nintendo DS", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val subtitle = composeRule.onNodeWithText("ROM", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the subtitle sits under the title", subtitle.top >= title.bottom - 0.5f)

        composeRule.onNode(hasClickAction()).performClick()
        composeRule.waitForIdle()
        assertEquals("the breadcrumb is the page's touch way back", 1, backs)
    }

    @Test
    fun `a wide trailing slot never cuts the breadcrumb short`() {
        val title = "Achievements / Tracked Games"
        var wideTrailing by mutableStateOf(false)
        composeRule.setContent {
            PfpScreenPreview {
                PfpDetailBreadcrumb(
                    title = title,
                    subtitle = "3 games",
                    onBack = {},
                    trailing = if (wideTrailing) {
                        { Column(Modifier.testTag("trailing").width(2000.dp).height(40.dp)) {} }
                    } else {
                        null
                    },
                )
            }
        }
        composeRule.waitForIdle()
        val alone = composeRule.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        composeRule.runOnIdle { wideTrailing = true }
        composeRule.waitForIdle()
        val withTrailing = composeRule.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val trailing = composeRule.onNodeWithTag("trailing", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        assertEquals("the breadcrumb keeps its full width beside a trailing slot", alone.width, withTrailing.width, 0.5f)
        assertTrue("the trailing slot sits after the breadcrumb", trailing.left >= withTrailing.right - 0.5f)
    }

    @Test
    fun `the body reports its real viewport height to the page`() {
        var reported = androidx.compose.ui.unit.Dp.Unspecified
        composeRule.setContent {
            PfpScreenPreview {
                PfpDetailScaffold(
                    modifier = Modifier.testTag("page"),
                    header = { PfpDetailBreadcrumb(title = "Nintendo DS", subtitle = "ROM", onBack = {}) },
                    footer = { PfpDetailHelperFooter(items = emptyList(), modifier = Modifier.testTag("footer")) },
                ) {
                    reported = LocalDetailViewportHeight.current
                    Column(Modifier.fillMaxWidth().height(2400.dp)) {}
                }
            }
        }
        composeRule.waitForIdle()

        val viewportPx = composeRule.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot.height
        val reportedPx = with(composeRule.density) { reported.toPx() }
        assertEquals("the page sizes its top band from the body's real viewport", viewportPx, reportedPx, 1f)
    }
}
