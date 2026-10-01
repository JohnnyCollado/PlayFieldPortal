package com.playfieldportal.core.ui.keyboard

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.navigation.SpanGridCursor
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.theme.PFPTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The drawn keyboard (Virtual Keyboard plan section 4, tests 6.10). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h468dp")
class VirtualKeyboardPanelTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(state: VirtualKeyboardState) {
        composeRule.setContent { PFPTheme { VirtualKeyboardPanel(state) } }
        composeRule.waitForIdle()
    }

    private val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)

    @Test fun `every key has a tag and the glyph keys have descriptions`() {
        show(VirtualKeyboardReducer.open(""))

        VirtualKeyboardLayout.rows(KeyboardLayer.LETTERS).forEachIndexed { row, keys ->
            keys.indices.forEach { cell -> composeRule.onNodeWithTag(virtualKeyTag(row, cell)).assertExists() }
        }
        listOf("Shift", "Space", "Backspace", "Done").forEach {
            composeRule.onNodeWithContentDescription(it).assertExists()
        }
    }

    @Test fun `exactly one key is selected, the focused one`() {
        show(VirtualKeyboardReducer.open("").copy(focus = SpanGridCursor(2, 3)))

        val selectedTags = composeRule.onAllNodes(selected).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.TestTag] }
        assertEquals(listOf(virtualKeyTag(2, 3)), selectedTags)
    }

    @Test fun `letters read uppercase while shift is on`() {
        show(VirtualKeyboardReducer.open("").copy(shift = ShiftMode.ONCE))

        composeRule.onNodeWithText("Q").assertExists()
        composeRule.onNodeWithText("q").assertDoesNotExist()
    }

    @Test fun `shift reports its state, caps lock included`() {
        show(VirtualKeyboardReducer.open("").copy(shift = ShiftMode.LOCKED))

        composeRule.onNodeWithText("Q").assertExists()
        composeRule.onNodeWithContentDescription("Shift")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Caps lock on"))
    }

    @Test fun `the symbols layer shows ABC and a disabled shift`() {
        show(VirtualKeyboardReducer.open("").copy(layer = KeyboardLayer.SYMBOLS))

        composeRule.onNodeWithText("ABC").assertExists()
        composeRule.onNodeWithContentDescription("Shift").assertIsNotEnabled()
    }

    @Test fun `the prompt bar lists the six prompts, START as Done before Close`() {
        assertEquals(
            listOf(
                ControllerPromptItem(GamepadAction.SELECT, "Type"),
                ControllerPromptItem(GamepadAction.CHANGE_SORT, "Delete"),
                ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Space"),
                ControllerPromptItem(listOf(GamepadAction.PREV_CATEGORY, GamepadAction.NEXT_CATEGORY), "Cursor"),
                ControllerPromptItem(GamepadAction.HOME, "Done"),
                ControllerPromptItem(GamepadAction.BACK, "Close"),
            ),
            VirtualKeyboardPrompts,
        )
    }
}
