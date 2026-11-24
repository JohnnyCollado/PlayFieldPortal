package com.playfieldportal.core.ui.keyboard

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.navigation.SpanGridCursor
import com.playfieldportal.core.ui.sound.MenuSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keyboard as a pure state machine over gamepad actions (Virtual Keyboard plan 6.5). */
class VirtualKeyboardReducerTest {

    private fun open(text: String = "") = VirtualKeyboardReducer.open(text)

    private fun VirtualKeyboardState.focusOn(row: Int, cell: Int) = copy(focus = SpanGridCursor(row, cell))

    private fun VirtualKeyboardState.press(action: GamepadAction) = VirtualKeyboardReducer.reduce(this, action)

    private fun KeyboardReduction.edits() = effects.filterIsInstance<KeyboardEffect.Edit>()

    private fun KeyboardReduction.sounds() = effects.filterIsInstance<KeyboardEffect.Sound>().map { it.sound }

    // Bottom row (3) cells: Shift 0, layer 1, Space 2, Backspace 3, Done 4.
    private val shift = 3 to 0
    private val layer = 3 to 1
    private val space = 3 to 2
    private val backspace = 3 to 3
    private val done = 3 to 4

    private fun VirtualKeyboardState.on(key: Pair<Int, Int>) = focusOn(key.first, key.second)

    @Test fun `opening starts on letters, shift off, focus on q, caret at the end`() {
        val state = open("abc")
        assertEquals(KeyboardLayer.LETTERS, state.layer)
        assertEquals(ShiftMode.OFF, state.shift)
        assertEquals(SpanGridCursor(0, 0), state.focus)
        assertEquals(TextEditBuffer("abc", 3), state.buffer)
    }

    @Test fun `select on a character inserts it once and focus stays`() {
        val result = open().focusOn(0, 1).press(GamepadAction.SELECT) // w
        assertEquals(listOf(KeyboardEffect.Edit("w", 1)), result.edits())
        assertEquals(SpanGridCursor(0, 1), result.state.focus)
    }

    @Test fun `shift then a letter types uppercase and clears shift`() {
        val shifted = open().on(shift).press(GamepadAction.SELECT).state
        assertEquals(ShiftMode.ONCE, shifted.shift)
        val result = shifted.focusOn(0, 0).press(GamepadAction.SELECT)
        assertEquals("Q", result.state.buffer.text)
        assertEquals(ShiftMode.OFF, result.state.shift)
    }

    @Test fun `a second shift press locks caps, a third releases it`() {
        val once = open().on(shift).press(GamepadAction.SELECT).state
        val locked = once.press(GamepadAction.SELECT).state
        assertEquals(ShiftMode.LOCKED, locked.shift)
        assertEquals(ShiftMode.OFF, locked.press(GamepadAction.SELECT).state.shift)
    }

    @Test fun `caps lock keeps every letter uppercase`() {
        val locked = open().on(shift).press(GamepadAction.SELECT).state.press(GamepadAction.SELECT).state
        val q = locked.focusOn(0, 0).press(GamepadAction.SELECT).state
        val qw = q.focusOn(0, 1).press(GamepadAction.SELECT).state
        assertEquals("QW", qw.buffer.text)
        assertEquals(ShiftMode.LOCKED, qw.shift)
    }

    @Test fun `shift leaves punctuation alone`() {
        val shifted = open().on(shift).press(GamepadAction.SELECT).state
        assertEquals("-", shifted.focusOn(1, 9).press(GamepadAction.SELECT).state.buffer.text)
        val again = open().on(shift).press(GamepadAction.SELECT).state
        assertEquals("@", again.focusOn(2, 9).press(GamepadAction.SELECT).state.buffer.text)
    }

    @Test fun `the layer key toggles layers, keeps focus and clears shift`() {
        val shifted = open().on(shift).press(GamepadAction.SELECT).state
        val symbols = shifted.on(layer).press(GamepadAction.SELECT).state
        assertEquals(KeyboardLayer.SYMBOLS, symbols.layer)
        assertEquals(ShiftMode.OFF, symbols.shift)
        assertEquals(SpanGridCursor(3, 1), symbols.focus)
        assertEquals(KeyboardLayer.LETTERS, symbols.press(GamepadAction.SELECT).state.layer)
    }

    @Test fun `shift on the symbols layer does nothing`() {
        val symbols = open().on(layer).press(GamepadAction.SELECT).state
        val result = symbols.on(shift).press(GamepadAction.SELECT)
        assertEquals(ShiftMode.OFF, result.state.shift)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun `focus row and column survive a layer switch`() {
        val state = open().on(layer).press(GamepadAction.SELECT).state
        assertEquals(SpanGridCursor(3, 1), state.focus)
        val typed = state.focusOn(1, 1).press(GamepadAction.SELECT).state
        assertEquals("#", typed.buffer.text)
    }

    @Test fun `the space key and its shortcut each insert one space, the shortcut without moving focus`() {
        assertEquals(" ", open().on(space).press(GamepadAction.SELECT).state.buffer.text)
        val shortcut = open().focusOn(2, 3).press(GamepadAction.OPEN_CONTEXT_MENU)
        assertEquals(" ", shortcut.state.buffer.text)
        assertEquals(SpanGridCursor(2, 3), shortcut.state.focus)
    }

    @Test fun `backspace key and its shortcut delete before the caret, nothing on empty text`() {
        assertEquals("a", open("ab").on(backspace).press(GamepadAction.SELECT).state.buffer.text)
        assertEquals("a", open("ab").press(GamepadAction.CHANGE_SORT).state.buffer.text)
        assertTrue(open().press(GamepadAction.CHANGE_SORT).effects.isEmpty())
        assertTrue(open().on(backspace).press(GamepadAction.SELECT).effects.isEmpty())
    }

    @Test fun `the shoulders move the caret and clamp at the ends`() {
        val left = open("ab").press(GamepadAction.PREV_CATEGORY)
        assertEquals(1, left.state.buffer.caret)
        assertEquals(listOf(KeyboardEffect.CaretMoved(1)), left.effects.filterIsInstance<KeyboardEffect.CaretMoved>())
        assertTrue(open("ab").press(GamepadAction.NEXT_CATEGORY).effects.isEmpty())
        assertTrue(open().press(GamepadAction.PREV_CATEGORY).effects.isEmpty())
    }

    @Test fun `a character typed after moving the caret lands at the caret`() {
        val moved = open("ac").press(GamepadAction.PREV_CATEGORY).state
        val result = moved.focusOn(2, 4).press(GamepadAction.SELECT) // b
        assertEquals(listOf(KeyboardEffect.Edit("abc", 2)), result.edits())
    }

    @Test fun `done emits Done and back emits Close`() {
        assertTrue(open().on(done).press(GamepadAction.SELECT).effects.contains(KeyboardEffect.Done))
        assertTrue(open().press(GamepadAction.BACK).effects.contains(KeyboardEffect.Close))
    }

    @Test fun `start is a shortcut to Done from anywhere on the keyboard`() {
        val result = open("abc").focusOn(2, 3).press(GamepadAction.HOME)
        assertTrue(result.effects.contains(KeyboardEffect.Done))
        assertEquals(listOf(MenuSound.CONFIRM), result.sounds())
        // The shortcut neither types nor moves the cursor.
        assertEquals(TextEditBuffer("abc", 3), result.state.buffer)
        assertEquals(SpanGridCursor(2, 3), result.state.focus)
    }

    @Test fun `directions move focus on the span grid and cycle around every edge`() {
        val down = open().press(GamepadAction.NAVIGATE_DOWN)
        assertEquals(SpanGridCursor(1, 0, anchorColumn = 0), down.state.focus)
        // Up from the top row comes round to the bottom row (Shift covers column 0).
        assertEquals(SpanGridCursor(3, 0, anchorColumn = 0), open().focusOn(0, 0).press(GamepadAction.NAVIGATE_UP).state.focus)
        // Right from the end of a row comes round to its start.
        assertEquals(0, open().focusOn(1, 9).press(GamepadAction.NAVIGATE_RIGHT).state.focus.cell)
        assertEquals(9, open().focusOn(1, 0).press(GamepadAction.NAVIGATE_LEFT).state.focus.cell)
    }

    @Test fun `every effect carries the agreed sound`() {
        assertEquals(listOf(MenuSound.SCROLL), open().press(GamepadAction.NAVIGATE_RIGHT).sounds())
        assertEquals(listOf(MenuSound.SELECT), open().press(GamepadAction.SELECT).sounds())
        assertEquals(listOf(MenuSound.SELECT), open("a").press(GamepadAction.CHANGE_SORT).sounds())
        assertEquals(listOf(MenuSound.SELECT), open().press(GamepadAction.OPEN_CONTEXT_MENU).sounds())
        assertEquals(listOf(MenuSound.SYSTEM_BROWSE), open().on(layer).press(GamepadAction.SELECT).sounds())
        assertEquals(listOf(MenuSound.CONFIRM), open().on(done).press(GamepadAction.SELECT).sounds())
        assertEquals(listOf(MenuSound.BACK), open().press(GamepadAction.BACK).sounds())
        assertEquals(listOf(MenuSound.SCROLL), open().focusOn(0, 0).press(GamepadAction.NAVIGATE_UP).sounds())
        assertEquals(emptyList<MenuSound>(), open().press(GamepadAction.CHANGE_SORT).sounds())
    }

    @Test fun `a host-side text replacement updates the text and clamps the caret`() {
        val state = VirtualKeyboardReducer.replaceText(open("abcdef"), "ab")
        assertEquals(TextEditBuffer("ab", 2), state.buffer)
    }

    // ── L2 Shift, R3 Caps Lock ───────────────────────────────────────────────

    @Test fun `L2 shifts for as long as it is held`() {
        val held = open().press(GamepadAction.SHIFT)
        assertEquals(ShiftMode.HELD, held.state.shift)
        assertEquals(listOf(MenuSound.SELECT), held.sounds())

        // Every letter typed during the hold is a capital: typing does not spend it.
        val q = held.state.press(GamepadAction.SELECT)
        assertEquals(listOf(KeyboardEffect.Edit("Q", 1)), q.edits())
        assertEquals(ShiftMode.HELD, q.state.shift)
        assertEquals(listOf(KeyboardEffect.Edit("QQ", 2)), q.state.press(GamepadAction.SELECT).edits())
    }

    @Test fun `releasing L2 goes back to lower case, silently`() {
        val held = open().press(GamepadAction.SHIFT).state
        val released = VirtualKeyboardReducer.shiftReleased(held)
        assertEquals(ShiftMode.OFF, released.state.shift)
        assertTrue(released.effects.isEmpty())
        assertEquals(listOf(KeyboardEffect.Edit("q", 1)), released.state.press(GamepadAction.SELECT).edits())
    }

    @Test fun `a held L2 that repeats its press changes nothing`() {
        val held = open().press(GamepadAction.SHIFT).state
        val again = held.press(GamepadAction.SHIFT)
        assertEquals(held, again.state)
        assertTrue(again.effects.isEmpty())
    }

    @Test fun `L2 does not disturb caps lock or the on-screen key's one-shot`() {
        val locked = open().press(GamepadAction.CAPS_LOCK).state
        assertEquals(ShiftMode.LOCKED, locked.press(GamepadAction.SHIFT).state.shift)
        assertEquals(ShiftMode.LOCKED, VirtualKeyboardReducer.shiftReleased(locked).state.shift)

        // The on-screen Shift key's one-shot is not a hold, so a stray release leaves it armed.
        val once = open().on(shift).press(GamepadAction.SELECT).state
        assertEquals(ShiftMode.ONCE, once.shift)
        assertEquals(ShiftMode.ONCE, VirtualKeyboardReducer.shiftReleased(once).state.shift)
    }

    @Test fun `R3 locks caps and a second press releases it`() {
        val locked = open().press(GamepadAction.CAPS_LOCK)
        assertEquals(ShiftMode.LOCKED, locked.state.shift)
        assertEquals(ShiftMode.OFF, locked.state.press(GamepadAction.CAPS_LOCK).state.shift)
    }

    @Test fun `shift and caps do nothing on the symbols layer`() {
        val symbols = open().on(layer).press(GamepadAction.SELECT).state
        assertEquals(symbols, symbols.press(GamepadAction.SHIFT).state)
        assertTrue(symbols.press(GamepadAction.CAPS_LOCK).effects.isEmpty())
    }
}
