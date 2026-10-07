package com.playfieldportal.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.ui.theme.PFPTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The system keyboard over a phone in landscape (400dp tall, Gboard ~250dp): the text-entry modal
 * squashed its field flat and lost its buttons, and the colour picker sat under the keyboard. Both
 * must keep the field full-height and Cancel / Confirm on screen above the keyboard, so a touch
 * user can always finish.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w880dp-h400dp")
class SystemKeyboardModalLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val keyboardHeight = 250.dp

    private fun show(keyboard: Dp, content: @Composable () -> Unit) {
        composeRule.setContent {
            PFPTheme {
                CompositionLocalProvider(LocalSystemKeyboardHeightOverride provides keyboard) { content() }
            }
        }
        composeRule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertAboveKeyboard(tag: String, screenTag: String) {
        val node = bounds(tag)
        val screen = bounds(screenTag)
        val keyboardTop = screen.bottom - with(composeRule.density) { keyboardHeight.toPx() }
        assertTrue("$tag top ${node.top} must be on screen (${screen.top})", node.top >= screen.top)
        assertTrue("$tag bottom ${node.bottom} must clear the keyboard ($keyboardTop)", node.bottom <= keyboardTop + 0.5f)
    }

    @Composable
    private fun TextEntry() {
        PfpTextEntryModal(
            title = "New Custom Memory Card",
            value = "",
            onValueChange = {},
            focus = PfpModalFocus.FIELD,
            onFocusChange = {},
            onConfirm = {},
            onCancel = {},
            placeholder = "Card name",
            confirmLabel = "Next",
        )
    }

    @Composable
    private fun ColorPicker() {
        HsvColorPickerDialog(
            title = "Custom Icon Color",
            state = HsvPickerState.fromArgb(0xFFFFFFFFL).copy(focus = HsvPickerField.HEX, editingHex = true),
            onStateChange = {},
            onConfirm = {},
            onCancel = {},
        )
    }

    @Test fun `text entry keeps its field full height above the keyboard`() {
        show(keyboardHeight) { TextEntry() }
        assertAboveKeyboard(PfpModalTags.FIELD, PfpModalTags.SCRIM)
        val fieldHeight = bounds(PfpModalTags.FIELD).height
        assertEquals(with(composeRule.density) { 48.dp.toPx() }, fieldHeight, 1f)
    }

    @Test fun `text entry keeps Cancel and Confirm reachable above the keyboard`() {
        show(keyboardHeight) { TextEntry() }
        assertAboveKeyboard(PfpModalTags.CANCEL, PfpModalTags.SCRIM)
        assertAboveKeyboard(PfpModalTags.CONFIRM, PfpModalTags.SCRIM)
    }

    @Test fun `text entry keeps the full card when no system keyboard is up`() {
        show(0.dp) { TextEntry() }
        composeRule.onNodeWithText("Name").assertExists()
    }

    @Test fun `colour picker keeps the hex field above the keyboard`() {
        show(keyboardHeight) { ColorPicker() }
        assertAboveKeyboard(HsvPickerTags.HEX_FIELD, HsvPickerTags.SCRIM)
    }

    @Test fun `colour picker keeps Apply and Cancel reachable above the keyboard`() {
        show(keyboardHeight) { ColorPicker() }
        assertAboveKeyboard(HsvPickerTags.APPLY, HsvPickerTags.SCRIM)
        assertAboveKeyboard(HsvPickerTags.CANCEL, HsvPickerTags.SCRIM)
    }

    // A tall screen (tablet, Fold inner) leaves room for the full card above the keyboard, so the
    // picker stays full-size — and must then sit in that room, not centred over the whole window.
    @Config(qualifiers = "w1280dp-h800dp")
    @Test fun `on a tall screen the full colour picker sits above the keyboard`() {
        show(keyboardHeight) { ColorPicker() }
        composeRule.onNodeWithTag(HsvPickerTags.HUE_BAR).assertExists()
        assertAboveKeyboard(HsvPickerTags.HEX_FIELD, HsvPickerTags.SCRIM)
        assertAboveKeyboard(HsvPickerTags.APPLY, HsvPickerTags.SCRIM)
        assertAboveKeyboard(HsvPickerTags.CANCEL, HsvPickerTags.SCRIM)
    }

    @Config(qualifiers = "w1280dp-h800dp")
    @Test fun `on a tall screen the full text entry card sits above the keyboard`() {
        show(keyboardHeight) { TextEntry() }
        composeRule.onNodeWithText("Name").assertExists()
        assertAboveKeyboard(PfpModalTags.CONFIRM, PfpModalTags.SCRIM)
    }

    @Test fun `colour picker keeps its sliders when no system keyboard is up`() {
        show(0.dp) { ColorPicker() }
        composeRule.onNodeWithTag(HsvPickerTags.HUE_BAR).assertExists()
    }
}
