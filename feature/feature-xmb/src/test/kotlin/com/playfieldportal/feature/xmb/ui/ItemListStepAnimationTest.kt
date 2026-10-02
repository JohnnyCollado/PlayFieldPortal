package com.playfieldportal.feature.xmb.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.ui.theme.PFPTheme
import com.playfieldportal.feature.xmb.viewmodel.XMBItem
import com.playfieldportal.themekit.XmbLayoutSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The item column's step motion (Item List Step Animation plan, task 2.2), driven frame by frame
 * with the clock held. Rest positions are the ones [ItemListRestLayoutBase] pins.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h468dp-369dpi")
class ItemListStepAnimationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val items = List(12) { XMBItem(id = "row$it", title = "Row $it") }
    private val barTopY = 40.dp
    private val belowTopY = 152.dp

    private var selected by mutableIntStateOf(2)
    private var token by mutableIntStateOf(0)
    private var columnKey by mutableStateOf<Any?>("a")
    private var moving by mutableStateOf(false)
    private lateinit var density: Density

    private fun show(initial: Int, startMoving: Boolean = false) {
        selected = initial
        moving = startMoving
        composeRule.setContent {
            PFPTheme {
                density = LocalDensity.current
                CompositionLocalProvider(
                    LocalXmbRowDecor provides XmbRowDecor(movingLabel = if (moving) "x" else null),
                ) {
                    XMBItemList(
                        items = items,
                        selectedIndex = selected,
                        onItemSelected = {},
                        onItemLongPress = {},
                        scrollToTopToken = token,
                        columnKey = columnKey,
                        barTopY = barTopY,
                        belowTopY = belowTopY,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
    }

    private fun change(block: () -> Unit) {
        // Publishing the write by hand: with the clock held nothing else delivers the notification,
        // and the next frame is then the one that recomposes.
        composeRule.runOnUiThread {
            block()
            Snapshot.sendApplyNotifications()
        }
    }

    private fun firstFrame() = composeRule.mainClock.advanceTimeByFrame()

    private fun topOf(i: Int): Float =
        composeRule.onNodeWithTag("xmbRow:row$i").fetchSemanticsNode().positionInRoot.y

    private fun exists(i: Int): Boolean =
        composeRule.onAllNodesWithTag("xmbRow:row$i").fetchSemanticsNodes().isNotEmpty()

    private val rowPx get() = with(density) { ROW_HEIGHT.roundToPx() }
    private val belowTopPx get() = with(density) { belowTopY.roundToPx() }
    private val prevTopPx
        get() = with(density) {
            val winPx = (ROW_HEIGHT / 2).roundToPx()
            val winTopPx = (barTopY - ROW_HEIGHT * XmbLayoutSpec.DEFAULT.previousItemRiseRows).roundToPx()
            winTopPx + (winPx - rowPx) / 2
        }

    private fun assertRestAt(sel: Int) {
        assertEquals("selected row $sel", belowTopPx.toFloat(), topOf(sel), 0f)
        assertEquals("row after $sel", (belowTopPx + rowPx).toFloat(), topOf(sel + 1), 0f)
        if (sel >= 1) assertEquals("previous of $sel", prevTopPx.toFloat(), topOf(sel - 1), 0f)
    }

    private fun settle() {
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun `a step down glides between the two rest positions then settles on today's layout`() {
        show(2)
        change { selected = 3 }
        firstFrame()
        composeRule.mainClock.advanceTimeBy(100)
        val y = topOf(3)
        val oldRest = (belowTopPx + rowPx).toFloat()
        assertTrue("row 3 at $y should be between ${belowTopPx} and $oldRest", y > belowTopPx && y < oldRest)
        settle()
        assertRestAt(3)
    }

    @Test
    fun `a step up glides too`() {
        show(3)
        change { selected = 2 }
        firstFrame()
        composeRule.mainClock.advanceTimeBy(100)
        val y = topOf(2)
        assertTrue("row 2 at $y", y > prevTopPx && y < belowTopPx)
        settle()
        assertRestAt(2)
    }

    @Test
    fun `a move snaps in the first frame`() {
        show(2, startMoving = true)
        change { selected = 3 }
        firstFrame()
        assertRestAt(3)
    }

    @Test
    fun `a scroll-to-top token bump snaps in the first frame`() {
        show(2)
        change { selected = 3; token++ }
        firstFrame()
        assertRestAt(3)
    }

    @Test
    fun `a column key change snaps in the first frame`() {
        show(2)
        change { selected = 3; columnKey = "b" }
        firstFrame()
        assertRestAt(3)
    }

    @Test
    fun `the outgoing copy shows today's minus-one layout in its first frame`() {
        show(4)
        change { selected = -1 }
        firstFrame()
        assertEquals(belowTopPx.toFloat(), topOf(0), 0f)
        assertEquals((belowTopPx + rowPx).toFloat(), topOf(1), 0f)
        assertTrue("no previous row", !exists(3))
        assertTrue("row 4 is outside the window", !exists(4))
    }

    @Test
    fun `a long jump snaps one row short then glides the last row`() {
        show(0)
        change { selected = 8 }
        firstFrame()
        assertEquals("row 7 seated at the selected slot", belowTopPx.toFloat(), topOf(7), 0f)
        assertEquals("row 8 one slot below", (belowTopPx + rowPx).toFloat(), topOf(8), 0f)
        composeRule.mainClock.advanceTimeBy(100)
        val y = topOf(8)
        assertTrue("row 8 at $y is gliding up", y > belowTopPx && y < belowTopPx + rowPx)
        settle()
        assertRestAt(8)
    }

    // ── The drill flyout's game column ────────────────────────────────────────

    private val games = List(12) { XMBItem(id = "game$it", title = "Game $it") }
    private val siblings = List(3) { XMBItem(id = "card$it", title = "Card $it") }

    private fun showFlyout(initial: Int, startMoving: Boolean = false) {
        selected = initial
        moving = startMoving
        composeRule.setContent {
            PFPTheme {
                density = LocalDensity.current
                CompositionLocalProvider(
                    LocalXmbRowDecor provides XmbRowDecor(movingLabel = if (moving) "x" else null),
                ) {
                    XmbDrillFlyout(
                        siblings = siblings,
                        siblingIndex = 0,
                        items = games,
                        selectedIndex = selected,
                        onItemSelected = {},
                        onItemLongPress = {},
                        scrollToTopToken = token,
                        columnKey = columnKey,
                        barTopY = barTopY,
                        belowTopY = belowTopY,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
    }

    private fun gameTop(i: Int): Float =
        composeRule.onNodeWithTag("xmbRow:game$i").fetchSemanticsNode().positionInRoot.y

    private fun gameExists(i: Int): Boolean =
        composeRule.onAllNodesWithTag("xmbRow:game$i").fetchSemanticsNodes().isNotEmpty()

    // Today's flyout placement: the dp offset from the anchor line, rounded once.
    private fun gameRestTop(i: Int, sel: Int): Float =
        with(density) { (belowTopY + ROW_HEIGHT * (i - sel)).roundToPx().toFloat() }

    private fun assertGameRestAt(sel: Int) {
        for (i in maxOf(0, sel - 2)..minOf(games.lastIndex, sel + 3)) {
            assertEquals("game $i at selection $sel", gameRestTop(i, sel), gameTop(i), 0f)
        }
    }

    @Test
    fun `the flyout game column glides a step and settles on today's contiguous layout`() {
        showFlyout(2)
        assertGameRestAt(2)
        change { selected = 3 }
        firstFrame()
        composeRule.mainClock.advanceTimeBy(100)
        val y = gameTop(3)
        val oldRest = gameRestTop(3, 2)
        assertTrue("game 3 at $y should be between ${belowTopPx} and $oldRest", y > belowTopPx && y < oldRest)
        settle()
        assertGameRestAt(3)
    }

    @Test
    fun `the flyout game column snaps in the first frame on a token bump and a column key change`() {
        showFlyout(2)
        change { selected = 3; token++ }
        firstFrame()
        assertGameRestAt(3)
        change { selected = 4; columnKey = "b" }
        firstFrame()
        assertGameRestAt(4)
    }

    @Test
    fun `the flyout game column snaps while a row is being moved`() {
        showFlyout(2, startMoving = true)
        change { selected = 3 }
        firstFrame()
        assertGameRestAt(3)
    }

    @Test
    fun `the flyout game column composes rows in transit and sheds them at rest`() {
        showFlyout(6)
        change { selected = 7 }
        firstFrame()
        composeRule.mainClock.advanceTimeBy(100)
        assertTrue("game 6 is still composed mid-glide", gameExists(6))
        settle()
        assertTrue("a row far above the window is gone", !gameExists(0))
    }

    @Test
    fun `the composed window follows the animated position in transit and returns to rest`() {
        show(2)
        assertTrue("row 5 is outside the rest window", !exists(5))
        change { selected = 3 }
        firstFrame()
        composeRule.mainClock.advanceTimeBy(100)
        assertTrue("row 5 is composed while rows are still rising", exists(5))
        assertTrue("row 6 never is", !exists(6))
        settle()
        assertTrue("row 1 left the window at rest", !exists(1))
    }
}
