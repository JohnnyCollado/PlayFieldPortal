package com.playfieldportal.feature.xmb.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.playfieldportal.core.ui.icons.CustomIcon
import com.playfieldportal.core.ui.preview.PfpScreenPreview
import com.playfieldportal.feature.xmb.viewmodel.ThemeIconGridState
import com.playfieldportal.feature.xmb.viewmodel.themeIconGridFor
import com.playfieldportal.themekit.CustomizableIcons
import com.playfieldportal.themekit.PtfIcons
import com.playfieldportal.themekit.ThemeIconChoices
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The theme icon grid shows one input family at a time: the pad's footer, or the Cancel pill and
 * touch prompt - never both - and a finger on it hands the shell to touch. Also pins how the grid's
 * state is assembled from the two tiers (order, the identical-image collapse, the empty case).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w880dp-h400dp")
class ThemeIconGridInputFamilyTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val still = CustomIcon.Still(ImageBitmap(2, 2))

    private fun slotEntry(name: String) = File(name) to (still as CustomIcon)

    private val grid: ThemeIconGridState = themeIconGridFor(
        slotKey = "catbar_music",
        slotName = "Music",
        themeName = "Ocean",
        slotIcons = mapOf(
            "catbar_settings" to slotEntry("catbar_settings.png"),
            "catbar_music" to slotEntry("catbar_music.png"),
        ),
        ptfIcons = mapOf(PtfIcons.SlotRef(2, 5) to slotEntry("2_5.png")),
        artKey = { it.name },
    )!!

    private var chosen: Int? = null
    private var touches = 0
    private var browsed: Int? = null

    // A real theme's worth of tiles: far more rows than the 400dp-tall screen shows.
    private val tall: ThemeIconGridState = themeIconGridFor(
        slotKey = "catbar_music",
        slotName = "Music",
        themeName = "Ocean",
        slotIcons = mapOf("catbar_settings" to slotEntry("catbar_settings.png")),
        ptfIcons = (0 until 40).associate { i -> PtfIcons.SlotRef(3, 100 + i * 2) to slotEntry("3_${100 + i * 2}.png") },
        artKey = { it.name },
    )!!

    private fun show(touch: Boolean, state: ThemeIconGridState = grid) {
        composeRule.setContent {
            PfpScreenPreview {
                ThemeIconGridOverlay(
                    grid = state,
                    onChoose = { chosen = it },
                    onBack = {},
                    showTouchControls = touch,
                    onTouchInput = { touches++ },
                    onTouchBrowse = { browsed = it },
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `touch mode shows the Cancel pill and the touch prompt and no controller footer`() {
        show(touch = true)
        composeRule.onNodeWithTag(ThemeIconGridTags.CANCEL).assertExists()
        composeRule.onNodeWithTag(ThemeIconGridTags.TOUCH_PROMPTS).assertExists()
        composeRule.onNodeWithTag(ThemeIconGridTags.PROMPTS).assertDoesNotExist()
    }

    @Test
    fun `controller mode shows the controller footer and no touch pill or prompt`() {
        show(touch = false)
        composeRule.onNodeWithTag(ThemeIconGridTags.PROMPTS).assertExists()
        composeRule.onNodeWithTag(ThemeIconGridTags.CANCEL).assertDoesNotExist()
        composeRule.onNodeWithTag(ThemeIconGridTags.TOUCH_PROMPTS).assertDoesNotExist()
    }

    @Test
    fun `the header names the theme and the slot and both sections are titled`() {
        show(touch = false)
        composeRule.onNodeWithText("Ocean · icon for Music").assertExists()
        composeRule.onNodeWithText(ThemeIconChoices.THEME_ICONS_TITLE).assertExists()
        composeRule.onNodeWithText(ThemeIconChoices.PTF_ICONS_TITLE).assertExists()
    }

    @Test
    fun `tapping a tile chooses its index and reports touch input`() {
        show(touch = true)
        composeRule.onNodeWithTag(ThemeIconGridTags.tile(2)).performClick()
        composeRule.waitForIdle()
        assertEquals(2, chosen)
        assertTrue("touches = $touches", touches >= 1)
    }

    @Test
    fun `the pad cursor on a tile below the screen scrolls it into view`() {
        val last = tall.files.lastIndex
        show(touch = false, state = tall.copy(cursor = last))
        composeRule.onNodeWithTag(ThemeIconGridTags.tile(last)).assertIsDisplayed()
    }

    @Test
    fun `the pad cursor moving back to the top scrolls the first tile into view`() {
        var state by mutableStateOf(tall.copy(cursor = tall.files.lastIndex))
        composeRule.setContent {
            PfpScreenPreview {
                ThemeIconGridOverlay(grid = state, onChoose = {}, onBack = {})
            }
        }
        composeRule.waitForIdle()
        state = state.copy(cursor = 0)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ThemeIconGridTags.tile(0)).assertIsDisplayed()
    }

    @Test
    fun `a finger scroll parks the cursor on a tile it left on screen`() {
        show(touch = true, state = tall)
        composeRule.onNodeWithTag(ThemeIconGridTags.GRID).performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        val parked = browsed
        assertTrue("browsed = $parked", parked != null && parked > 0)
        composeRule.onNodeWithTag(ThemeIconGridTags.tile(parked!!)).assertIsDisplayed()
    }

    @Test
    fun `state lists slot icons in registry order then the PSP extras, with a flat file per tile`() {
        assertEquals(listOf(2, 1), grid.sectionSizes)
        val slotLabels = CustomizableIcons.ALL
            .filter { it.key == "catbar_music" || it.key == "catbar_settings" }
            .map { it.displayName }
        assertEquals(slotLabels + "TV", grid.sections.flatMap { it.choices }.map { it.label })
        assertEquals(3, grid.files.size)
        assertEquals(3, grid.icons.size)
        assertEquals(File("2_5.png"), grid.files.last())
    }

    @Test
    fun `identical slot images collapse into one tile labelled by the first slot in registry order`() {
        val collapsed = themeIconGridFor(
            slotKey = "catbar_music",
            slotName = "Music",
            themeName = "Ocean",
            slotIcons = mapOf(
                "catbar_settings" to slotEntry("a.png"),
                "catbar_music" to slotEntry("b.png"),
            ),
            ptfIcons = emptyMap(),
            artKey = { "same" },
        )!!
        assertEquals(1, collapsed.sections.single().choices.size)
        assertEquals(1, collapsed.files.size)
    }

    @Test
    fun `no icons at all gives no grid`() {
        val none = themeIconGridFor(
            slotKey = "catbar_music",
            slotName = "Music",
            themeName = "Ocean",
            slotIcons = emptyMap(),
            ptfIcons = emptyMap(),
            artKey = { it.name },
        )
        assertNull(none)
    }
}
