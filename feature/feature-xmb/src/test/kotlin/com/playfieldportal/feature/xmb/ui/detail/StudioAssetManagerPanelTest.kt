package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.ui.graphics.Color
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

/** The asset manager shows one input family at a time: pills in touch mode, glyph prompts otherwise. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h468dp")
class StudioAssetManagerPanelTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var closes = 0

    private fun show(touch: Boolean) {
        composeRule.setContent {
            PFPTheme {
                StudioAssetManagerPanel(
                    kindLabel = "Screenshots",
                    assets = emptyList(),
                    focusedIndex = 0,
                    busy = false,
                    showTouchControls = touch,
                    accent = Color.Cyan,
                    onFocus = {},
                    onMove = {},
                    onMakePrimary = {},
                    onClose = { closes++ },
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test fun `touch mode closes with a tappable pill and draws no controller text`() {
        show(touch = true)

        composeRule.onAllNodesWithText("Ⓑ  CLOSE").assertCountEquals(0)
        composeRule.onAllNodesWithText("Ⓛ Ⓡ  MOVE      Ⓐ  MAKE FIRST").assertCountEquals(0)
        composeRule.onAllNodesWithText("Move").assertCountEquals(0)
        composeRule.onNodeWithText("Close").performClick()

        assertEquals(1, closes)
    }

    @Test fun `controller mode uses the prompt bar and has no touch pills`() {
        show(touch = false)

        composeRule.onAllNodesWithText("Ⓑ  CLOSE").assertCountEquals(0)
        composeRule.onAllNodesWithText("Ⓛ Ⓡ  MOVE      Ⓐ  MAKE FIRST").assertCountEquals(0)
        composeRule.onAllNodesWithText("Up").assertCountEquals(0)
        composeRule.onNodeWithText("Move").assertExists()
        composeRule.onNodeWithText("Close").assertExists()
    }
}
