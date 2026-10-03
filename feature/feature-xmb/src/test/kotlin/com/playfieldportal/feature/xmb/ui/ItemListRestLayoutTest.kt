package com.playfieldportal.feature.xmb.ui

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.ui.theme.PFPTheme
import com.playfieldportal.feature.xmb.viewmodel.XMBItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Today's at-rest item column, measured in px (Item List Step Animation plan, task 1.1). The step
 * animation must reproduce these positions exactly once it settles, so this runs on the unmodified
 * list first and keeps running after every later task.
 *
 * The rows are located by the `xmbRow:<id>` tag, which sits before each row's own scale, so the
 * bounds are the layout slot and not the scaled drawing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ItemListRestLayoutBase {

    @get:Rule
    val composeRule = createComposeRule()

    private val items = List(8) { XMBItem(id = "row$it", title = "Row $it") }
    private val barTopY = 40.dp
    private val belowTopY = 152.dp
    private val screenHeight = 468.dp

    private fun show(selectedIndex: Int): Density {
        lateinit var density: Density
        composeRule.setContent {
            PFPTheme {
                density = LocalDensity.current
                XMBItemList(
                    items = items,
                    selectedIndex = selectedIndex,
                    onItemSelected = {},
                    onItemLongPress = {},
                    barTopY = barTopY,
                    belowTopY = belowTopY,
                )
            }
        }
        composeRule.waitForIdle()
        return density
    }

    private fun topOf(id: String): Float =
        composeRule.onNodeWithTag("xmbRow:$id").fetchSemanticsNode().positionInRoot.y

    private fun assertRestLayout(selectedIndex: Int) {
        val density = show(selectedIndex)
        with(density) {
            val rowPx = ROW_HEIGHT.roundToPx()
            val belowTopPx = belowTopY.roundToPx()
            val winPx = (ROW_HEIGHT / 2).roundToPx()
            val rise = com.playfieldportal.themekit.XmbLayoutSpec.DEFAULT.previousItemRiseRows
            val winTopPx = (barTopY - ROW_HEIGHT * rise).roundToPx()
            val rowsBelow = ((screenHeight.value - belowTopY.value) / ROW_HEIGHT.value).toInt()
                .coerceAtLeast(1)

            val below = selectedIndex until minOf(items.size, selectedIndex + rowsBelow)
            val previous = (selectedIndex - 1).takeIf { selectedIndex in 1..items.lastIndex }

            items.indices.forEach { i ->
                when {
                    i in below -> assertEquals(
                        "row $i below slot, selected $selectedIndex",
                        (belowTopPx + (i - selectedIndex) * rowPx).toFloat(),
                        topOf("row$i"),
                    )
                    i == previous -> assertEquals(
                        "previous row $i, selected $selectedIndex",
                        (winTopPx + (winPx - rowPx) / 2).toFloat(),
                        topOf("row$i"),
                    )
                    else -> composeRule.onNodeWithTag("xmbRow:row$i").assertDoesNotExist()
                }
            }
        }
    }

    @Test fun `first row selected has no previous row`() = assertRestLayout(0)

    @Test fun `second row selected shows the first as previous`() = assertRestLayout(1)

    @Test fun `middle row selected`() = assertRestLayout(3)

    @Test fun `last row selected`() = assertRestLayout(7)
}

/** The Thor and Odin 3: 2.306 px per dp, so ROW_HEIGHT is not a whole number of pixels. */
@Config(sdk = [34], qualifiers = "w833dp-h468dp-369dpi")
class ItemListRestLayoutTest : ItemListRestLayoutBase()

/** A plain tvdpi screen: 1.33 px per dp. */
@Config(sdk = [34], qualifiers = "w833dp-h468dp-tvdpi")
class ItemListRestLayoutTvdpiTest : ItemListRestLayoutBase()
