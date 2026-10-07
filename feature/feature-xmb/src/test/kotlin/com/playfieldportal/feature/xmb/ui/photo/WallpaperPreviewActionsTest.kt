package com.playfieldportal.feature.xmb.ui.photo

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.playfieldportal.core.ui.theme.PFPTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The wallpaper preview offers Apply / Cancel as pills under touch and as pad prompts otherwise. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h468dp")
class WallpaperPreviewActionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var applies = 0
    private var cancels = 0

    private fun show(touch: Boolean, applying: Boolean = false) {
        composeRule.setContent {
            PFPTheme {
                WallpaperPreviewActions(
                    showTouchControls = touch,
                    applying = applying,
                    onApply = { applies++ },
                    onCancel = { cancels++ },
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test fun `touch mode draws one tappable Apply and Cancel`() {
        show(touch = true)

        composeRule.onAllNodesWithText("Apply").assertCountEquals(1)
        composeRule.onAllNodesWithText("Cancel").assertCountEquals(1)
        composeRule.onNodeWithText("Apply").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(1, applies)
        assertEquals(1, cancels)
    }

    @Test fun `controller mode draws the prompts once and no buttons`() {
        show(touch = false)

        composeRule.onAllNodesWithText("Apply").assertCountEquals(1)
        composeRule.onAllNodesWithText("Cancel").assertCountEquals(1)
        composeRule.onNodeWithText("Apply").performClick()

        assertEquals("a prompt is not a button", 0, applies)
    }

    @Test fun `touch Apply does nothing while the wallpaper is being applied`() {
        show(touch = true, applying = true)

        composeRule.onNodeWithText("Applying…").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(0, applies)
        assertEquals(0, cancels)
    }
}
