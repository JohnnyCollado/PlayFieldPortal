package com.playfieldportal.feature.xmb.ui.apppicker

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.playfieldportal.core.ui.preview.PfpScreenPreview
import com.playfieldportal.feature.xmb.ui.PickerHeaderTags
import com.playfieldportal.feature.xmb.viewmodel.AppPickerState
import com.playfieldportal.feature.xmb.viewmodel.AppPickerTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The App Picker's header on the helper-button standard (ARCHITECTURE.md ▸ Conventions): a ◀
 * breadcrumb that backs out like B, and in touch mode only, Search then ✓ Done pills.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w880dp-h400dp")
class AppPickerHeaderStandardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var backs = 0
    private var searches = mutableListOf<Boolean>()
    private var applies = 0

    private fun show(touch: Boolean, searchActive: Boolean = false) {
        composeRule.setContent {
            PfpScreenPreview {
                AppPickerScreen(
                    state = AppPickerState(
                        title = "Add Apps",
                        target = AppPickerTarget.AndroidGames(platformId = "android"),
                        apps = emptyList(),
                        searchActive = searchActive,
                    ),
                    onTileTapped = {},
                    onTouchBrowse = {},
                    onHeaderBack = { backs++ },
                    onSearchToggle = { searches += it },
                    onSearchChange = {},
                    onSearchDone = {},
                    onApply = { applies++ },
                    onConfirmRemoval = {},
                    onCancelRemoval = {},
                    showTouchControls = touch,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `the breadcrumb is a back triangle that backs out`() {
        show(touch = false)
        composeRule.onNodeWithText("◀").assertExists()
        composeRule.onNodeWithText("‹").assertDoesNotExist()
        composeRule.onNodeWithTag(PickerHeaderTags.BACK).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `touch mode carries Search and Done pills that act`() {
        show(touch = true)
        composeRule.onNodeWithTag(PickerHeaderTags.SEARCH).performClick()
        composeRule.onNodeWithTag(PickerHeaderTags.DONE).performClick()
        assertEquals(listOf(true), searches)
        assertEquals(1, applies)
        composeRule.onNodeWithText("Apply").assertDoesNotExist()
    }

    @Test
    fun `controller mode has no pills - X and START are in the footer`() {
        show(touch = false)
        composeRule.onNodeWithTag(PickerHeaderTags.SEARCH).assertDoesNotExist()
        composeRule.onNodeWithTag(PickerHeaderTags.DONE).assertDoesNotExist()
    }

    @Test
    fun `an open search steps the Search pill aside - the breadcrumb closes it`() {
        show(touch = true, searchActive = true)
        composeRule.onNodeWithTag(PickerHeaderTags.SEARCH).assertDoesNotExist()
        composeRule.onNodeWithTag(PickerHeaderTags.DONE).assertExists()
    }
}
