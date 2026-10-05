package com.playfieldportal.core.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.playfieldportal.core.ui.keyboard.InputSource
import com.playfieldportal.core.ui.keyboard.LocalVirtualKeyboard
import com.playfieldportal.core.ui.keyboard.VirtualKeyboardBottomReserve
import com.playfieldportal.core.ui.keyboard.VirtualKeyboardController
import com.playfieldportal.core.ui.theme.PFPTheme
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The colour picker's Hex field with PFP's keyboard up, on the Odin 3's 468dp-tall screen: the
 * field and the swatch must both sit in what the keyboard leaves, for every caller's shape (Icon
 * Color and Color Scheme without the contrast strip, Font Colour with it).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h468dp")
class HsvColorPickerKeyboardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val keyboard = VirtualKeyboardController()
    private var state by mutableStateOf(HsvPickerState.fromArgb(0xFF9DBEF5L).copy(focus = HsvPickerField.HEX))

    private fun show(withContrast: Boolean, typing: Boolean = true) {
        keyboard.inputSource = InputSource.CONTROLLER
        composeRule.setContent {
            PFPTheme {
                CompositionLocalProvider(LocalVirtualKeyboard provides keyboard) {
                    HsvColorPickerDialog(
                        title = "Custom Icon Color",
                        state = state,
                        onStateChange = { state = it },
                        onConfirm = {},
                        onCancel = {},
                        contrastAnchors = if (withContrast) Color.Black to Color.White else null,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        if (!typing) return
        state = state.copy(editingHex = true)
        composeRule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertAboveKeyboard(tag: String) {
        val node = bounds(tag)
        val screen = bounds(HsvPickerTags.SCRIM)
        val keyboardTop = screen.bottom - with(composeRule.density) { VirtualKeyboardBottomReserve.toPx() }
        assertTrue("keyboard must be open", keyboard.isOpen)
        assertTrue("$tag top ${node.top} must be on screen (${screen.top})", node.top >= screen.top)
        assertTrue("$tag bottom ${node.bottom} must clear the keyboard ($keyboardTop)", node.bottom <= keyboardTop)
    }

    @Test fun `the hex field sits above the open keyboard`() {
        show(withContrast = false)
        assertAboveKeyboard(HsvPickerTags.HEX_FIELD)
    }

    @Test fun `the swatch stays in view while typing hex`() {
        show(withContrast = false)
        assertAboveKeyboard(HsvPickerTags.SWATCH)
    }

    @Test fun `the hex field comes first, between the swatch and the bars`() {
        // Keyboard closed: with it up the bars scroll out of the shortened card.
        show(withContrast = false, typing = false)
        val hex = bounds(HsvPickerTags.HEX_FIELD)
        assertTrue("hex below the swatch", hex.top >= bounds(HsvPickerTags.SWATCH).bottom)
        assertTrue("hex above the hue bar", hex.bottom <= bounds(HsvPickerTags.HUE_BAR).top)
    }

    @Test fun `tapping the hex field raises only the system keyboard`() {
        keyboard.inputSource = InputSource.CONTROLLER
        composeRule.setContent {
            PFPTheme {
                CompositionLocalProvider(LocalVirtualKeyboard provides keyboard) {
                    HsvColorPickerDialog(
                        title = "Custom Icon Color",
                        state = state,
                        onStateChange = { state = it },
                        onConfirm = {},
                        onCancel = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HsvPickerTags.HEX_FIELD).performClick()
        composeRule.waitForIdle()

        assertTrue("the tap counts as editing hex", state.editingHex)
        assertNull("PFP's keyboard must stay closed on a tap", keyboard.session.value)
    }

    @Test fun `with the contrast strip the hex field and swatch still clear the keyboard`() {
        show(withContrast = true)
        assertAboveKeyboard(HsvPickerTags.HEX_FIELD)
        assertAboveKeyboard(HsvPickerTags.SWATCH)
    }
}
