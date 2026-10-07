package com.playfieldportal.feature.xmb.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.playfieldportal.core.ui.preview.PfpScreenPreview
import com.playfieldportal.themekit.XmbLayoutAdjust
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Adjust XMB Layout shows one input family at a time: the pad's prompts, or the touch buttons —
 * never both — and any finger on it hands the shell to touch, so the buttons appear when needed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w880dp-h400dp")
class XmbLayoutAdjustInputFamilyTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var touches = 0

    private fun show(touch: Boolean) {
        composeRule.setContent {
            PfpScreenPreview {
                XmbLayoutAdjustOverlay(
                    draft = XmbLayoutAdjust(),
                    slidersVisible = false,
                    onScale = {},
                    onHorizontal = {},
                    onVertical = {},
                    onToggleSliders = {},
                    onReset = {},
                    onSave = {},
                    onCancel = {},
                    showTouchControls = touch,
                    onTouchInput = { touches++ },
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `controller mode shows the prompts and no touch buttons`() {
        show(touch = false)
        composeRule.onNodeWithTag(XmbLayoutAdjustTags.PROMPTS).assertExists()
        composeRule.onNodeWithTag(XmbLayoutAdjustTags.TOUCH_BUTTONS).assertDoesNotExist()
    }

    @Test
    fun `touch mode shows the buttons and no controller prompts`() {
        show(touch = true)
        composeRule.onNodeWithTag(XmbLayoutAdjustTags.TOUCH_BUTTONS).assertExists()
        composeRule.onNodeWithTag(XmbLayoutAdjustTags.PROMPTS).assertDoesNotExist()
    }

    @Test
    fun `a finger anywhere on the editor reports touch input`() {
        show(touch = false)
        composeRule.onRoot().performTouchInput { click(center) }
        composeRule.waitForIdle()
        assertTrue("touches = $touches", touches >= 1)
    }
}
