package com.playfieldportal.feature.xmb.video

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

/** The error state's exit: a tappable Back pill in touch mode, the controller prompt otherwise. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h468dp")
class VideoErrorBackPromptTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var backs = 0

    private fun show(touch: Boolean) {
        composeRule.setContent {
            PFPTheme { VideoErrorBackPrompt(showTouchControls = touch, onBack = { backs++ }) }
        }
        composeRule.waitForIdle()
    }

    @Test fun `touch mode shows a Back pill that exits`() {
        show(touch = true)

        composeRule.onAllNodesWithText("Go back").assertCountEquals(0)
        composeRule.onNodeWithText("Back").performClick()

        assertEquals(1, backs)
    }

    @Test fun `controller mode shows the Go back prompt and no pill`() {
        show(touch = false)

        composeRule.onNodeWithText("Go back").assertExists()
        composeRule.onAllNodesWithText("Back").assertCountEquals(0)
    }
}
