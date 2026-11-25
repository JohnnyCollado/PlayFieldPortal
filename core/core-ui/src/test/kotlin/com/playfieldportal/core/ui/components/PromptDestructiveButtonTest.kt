package com.playfieldportal.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.playfieldportal.core.ui.theme.PFPTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A destructive prompt button (Uninstall, Delete) is a dim grey on the silver: quiet, so it is not
 * pressed by accident, and not the bright red that jumped off the silver. Nothing in it reads red.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h468dp")
class PromptDestructiveButtonTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the destructive button has no red in it`() {
        composeRule.setContent {
            PFPTheme {
                Box(Modifier.fillMaxSize().background(Color.Gray)) {
                    PfpConfirmModal(
                        title = "Uninstall CPU-Z?", message = "Sure?", confirmLabel = "Uninstall",
                        focus = PfpModalFocus.CANCEL, onConfirm = {}, onCancel = {}, destructive = true,
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()
        val bounds = composeRule.onNodeWithTag(PfpModalTags.CONFIRM, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val image = composeRule.onRoot().captureToImage().toPixelMap()
        var reddest = 0f
        for (x in bounds.left.toInt() until bounds.right.toInt()) for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
            val c = image[x, y]
            reddest = maxOf(reddest, c.red - maxOf(c.green, c.blue))
        }
        assertTrue("the button's reddest pixel leans red by ${"%.2f".format(reddest)}", reddest < 0.08f)
    }
}
