package com.playfieldportal.feature.xmb.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.playfieldportal.core.ui.preview.PfpScreenPreview
import com.playfieldportal.feature.xmb.viewmodel.MusicTrackPickerState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The track picker on the helper-button standard: back is a ◀ breadcrumb at the left in both input
 * modes, so touch mode carries no Cancel pill beside the ✓ pill.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w880dp-h400dp")
class MusicTrackPickerHeaderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var dismissed = 0

    private fun show(touch: Boolean) {
        composeRule.setContent {
            PfpScreenPreview {
                MusicTrackPicker(
                    state = MusicTrackPickerState(playlistId = 1L, playlistName = "Road Trip", tracks = emptyList()),
                    onActivateAt = {},
                    onConfirm = {},
                    onDismiss = { dismissed++ },
                    showTouchControls = touch,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `the breadcrumb backs out in touch mode`() {
        show(touch = true)
        composeRule.onNodeWithTag(PickerHeaderTags.BACK).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `the breadcrumb backs out in controller mode`() {
        show(touch = false)
        composeRule.onNodeWithTag(PickerHeaderTags.BACK).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `touch mode has no Cancel pill beside the breadcrumb`() {
        show(touch = true)
        composeRule.onNodeWithText("Cancel").assertDoesNotExist()
        composeRule.onNodeWithTag(PickerHeaderTags.DONE).assertExists()
    }
}
