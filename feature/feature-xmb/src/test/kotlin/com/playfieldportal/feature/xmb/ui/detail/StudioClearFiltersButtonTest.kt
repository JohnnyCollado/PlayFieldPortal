package com.playfieldportal.feature.xmb.ui.detail

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

/** "Clear Filters" is a tappable pill in touch mode and a Confirm prompt otherwise, never both. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h468dp")
class StudioClearFiltersButtonTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var clears = 0

    private fun show(touch: Boolean) {
        composeRule.setContent {
            PFPTheme { StudioClearFiltersButton(showTouchControls = touch, onClick = { clears++ }) }
        }
        composeRule.waitForIdle()
    }

    @Test fun `touch mode shows one tappable pill`() {
        show(touch = true)

        composeRule.onAllNodesWithText("Clear Filters").assertCountEquals(1)
        composeRule.onNodeWithText("Clear Filters").performClick()

        assertEquals(1, clears)
    }

    @Test fun `controller mode shows the prompt and it stays tappable`() {
        show(touch = false)

        composeRule.onAllNodesWithText("Clear Filters").assertCountEquals(1)
        composeRule.onNodeWithText("Clear Filters").performClick()

        assertEquals(1, clears)
    }
}
