package com.playfieldportal.core.ui.keyboard

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.navigation.NavigationDirection
import com.playfieldportal.core.navigation.SpanGridCursor
import com.playfieldportal.core.navigation.spanGridMove
import com.playfieldportal.core.ui.sound.MenuSound

/** Shift: off, for the next letter only, or locked until pressed again. */
enum class ShiftMode {
    OFF, ONCE, LOCKED;

    /** Shift's own press: off → once → locked → off. */
    fun next(): ShiftMode = when (this) {
        OFF -> ONCE
        ONCE -> LOCKED
        LOCKED -> OFF
    }

    /** After a typed character: a one-shot shift is spent, a lock holds. */
    fun afterTyping(): ShiftMode = if (this == LOCKED) LOCKED else OFF
}

/** Everything the open keyboard shows: the text, the layer, shift, and the focused key. */
data class VirtualKeyboardState(
    val buffer: TextEditBuffer,
    val layer: KeyboardLayer = KeyboardLayer.LETTERS,
    val shift: ShiftMode = ShiftMode.OFF,
    val focus: SpanGridCursor = INITIAL_FOCUS,
) {
    val focusedKey: VirtualKey?
        get() = VirtualKeyboardLayout.rows(layer).getOrNull(focus.row)?.getOrNull(focus.cell)

    companion object {
        /** `q`: row 1, first key. */
        val INITIAL_FOCUS = SpanGridCursor(1, 0)
    }
}

/** What a press asks the host to do, beyond showing the new state. */
sealed interface KeyboardEffect {
    /** The text changed; the caret moved with it. */
    data class Edit(val text: String, val caret: Int) : KeyboardEffect
    data class CaretMoved(val caret: Int) : KeyboardEffect
    data object Done : KeyboardEffect
    data object Close : KeyboardEffect
    data class Sound(val sound: MenuSound) : KeyboardEffect
}

data class KeyboardReduction(val state: VirtualKeyboardState, val effects: List<KeyboardEffect> = emptyList())

/**
 * The keyboard as a pure state machine over [GamepadAction]s (Virtual Keyboard plan section 1).
 * SELECT presses the focused key; X/Y are Delete/Space; the shoulders move the caret; START is
 * Done; BACK closes.
 * A press that changes nothing returns the state unchanged with no effects — and no sound.
 */
object VirtualKeyboardReducer {

    fun open(text: String, maxLength: Int? = null): VirtualKeyboardState =
        VirtualKeyboardState(buffer = TextEditBuffer.atEnd(text, maxLength))

    fun replaceText(state: VirtualKeyboardState, text: String): VirtualKeyboardState =
        state.copy(buffer = state.buffer.replaceText(text))

    fun reduce(state: VirtualKeyboardState, action: GamepadAction): KeyboardReduction = when (action) {
        GamepadAction.NAVIGATE_UP    -> move(state, NavigationDirection.UP)
        GamepadAction.NAVIGATE_DOWN  -> move(state, NavigationDirection.DOWN)
        GamepadAction.NAVIGATE_LEFT  -> move(state, NavigationDirection.LEFT)
        GamepadAction.NAVIGATE_RIGHT -> move(state, NavigationDirection.RIGHT)
        GamepadAction.SELECT         -> press(state)
        GamepadAction.CHANGE_SORT    -> edit(state, state.buffer.backspace())
        GamepadAction.OPEN_CONTEXT_MENU -> edit(state, state.buffer.insert(" "))
        GamepadAction.PREV_CATEGORY  -> caret(state, state.buffer.caretLeft())
        GamepadAction.NEXT_CATEGORY  -> caret(state, state.buffer.caretRight())
        GamepadAction.BACK           -> KeyboardReduction(state, listOf(KeyboardEffect.Close, KeyboardEffect.Sound(MenuSound.BACK)))
        // START: Done from anywhere, without walking the cursor down to the Done key.
        GamepadAction.HOME           -> done(state)
    }

    private fun move(state: VirtualKeyboardState, direction: NavigationDirection): KeyboardReduction {
        val next = spanGridMove(VirtualKeyboardLayout.spans(state.layer), state.focus, direction)
            ?: return KeyboardReduction(state)
        return KeyboardReduction(state.copy(focus = next), listOf(KeyboardEffect.Sound(MenuSound.SCROLL)))
    }

    private fun press(state: VirtualKeyboardState): KeyboardReduction = when (val key = state.focusedKey) {
        is VirtualKey.Character -> {
            val char = if (state.shift != ShiftMode.OFF) key.char.uppercaseChar() else key.char
            edit(state.copy(shift = state.shift.afterTyping()), state.buffer.insert(char.toString()))
        }
        is VirtualKey.Shift ->
            if (key.enabled) {
                KeyboardReduction(state.copy(shift = state.shift.next()), listOf(KeyboardEffect.Sound(MenuSound.SELECT)))
            } else {
                KeyboardReduction(state)
            }
        is VirtualKey.LayerSwitch -> {
            val layer = if (state.layer == KeyboardLayer.LETTERS) KeyboardLayer.SYMBOLS else KeyboardLayer.LETTERS
            // Both layers share one shape, so the focus row and cell stay valid.
            KeyboardReduction(state.copy(layer = layer, shift = ShiftMode.OFF), listOf(KeyboardEffect.Sound(MenuSound.SYSTEM_BROWSE)))
        }
        VirtualKey.Space -> edit(state, state.buffer.insert(" "))
        VirtualKey.Backspace -> edit(state, state.buffer.backspace())
        VirtualKey.Done -> done(state)
        null -> KeyboardReduction(state)
    }

    private fun done(state: VirtualKeyboardState) =
        KeyboardReduction(state, listOf(KeyboardEffect.Done, KeyboardEffect.Sound(MenuSound.CONFIRM)))

    // [base] carries any non-buffer change (a spent shift) even when the edit itself is a no-op.
    private fun edit(base: VirtualKeyboardState, buffer: TextEditBuffer): KeyboardReduction {
        if (buffer == base.buffer) return KeyboardReduction(base)
        return KeyboardReduction(
            base.copy(buffer = buffer),
            listOf(KeyboardEffect.Edit(buffer.text, buffer.caret), KeyboardEffect.Sound(MenuSound.SELECT)),
        )
    }

    private fun caret(state: VirtualKeyboardState, buffer: TextEditBuffer): KeyboardReduction {
        if (buffer == state.buffer) return KeyboardReduction(state)
        return KeyboardReduction(
            state.copy(buffer = buffer),
            listOf(KeyboardEffect.CaretMoved(buffer.caret), KeyboardEffect.Sound(MenuSound.SCROLL)),
        )
    }
}
