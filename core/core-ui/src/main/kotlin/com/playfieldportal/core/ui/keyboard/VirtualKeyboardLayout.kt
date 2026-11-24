package com.playfieldportal.core.ui.keyboard

/** One key of the virtual keyboard. [span] is its width in grid columns (each row spans ten). */
sealed interface VirtualKey {
    val span: Int

    data class Character(val char: Char) : VirtualKey {
        override val span: Int get() = 1
    }

    /** Shift: one press for the next letter, two for caps lock. Disabled on the symbols layer. */
    data class Shift(val enabled: Boolean) : VirtualKey {
        override val span: Int get() = 1
    }

    /** Switches letters ↔ symbols; [label] names the layer it switches to. */
    data class LayerSwitch(val label: String) : VirtualKey {
        override val span: Int get() = 2
    }

    data object Space : VirtualKey {
        override val span: Int get() = 4
    }

    data object Backspace : VirtualKey {
        override val span: Int get() = 1
    }

    data object Done : VirtualKey {
        override val span: Int get() = 2
    }
}

enum class KeyboardLayer { LETTERS, SYMBOLS }

/**
 * Ten columns, four rows per layer, laid out as Gboard does (2026-11-24): letters with no digit row,
 * digits on ?123. Four rows let the keyboard match the system keyboard's height with keys about its
 * size; the symbols page keeps the common twenty (^ = [ ] { } \ | < > left out).
 */
object VirtualKeyboardLayout {

    const val COLUMNS = 10

    private fun row(chars: String): List<VirtualKey> = chars.map { VirtualKey.Character(it) }

    private fun bottomRow(layer: KeyboardLayer): List<VirtualKey> = listOf(
        VirtualKey.Shift(enabled = layer == KeyboardLayer.LETTERS),
        VirtualKey.LayerSwitch(if (layer == KeyboardLayer.LETTERS) "?123" else "ABC"),
        VirtualKey.Space,
        VirtualKey.Backspace,
        VirtualKey.Done,
    )

    private val letters: List<List<VirtualKey>> = listOf(
        row("qwertyuiop"),
        row("asdfghjkl-"),
        row("zxcvbnm_.@"),
        bottomRow(KeyboardLayer.LETTERS),
    )

    private val symbols: List<List<VirtualKey>> = listOf(
        row("1234567890"),
        row("@#$%&*()-+"),
        row("!?'\",.:;_/"),
        bottomRow(KeyboardLayer.SYMBOLS),
    )

    fun rows(layer: KeyboardLayer): List<List<VirtualKey>> = when (layer) {
        KeyboardLayer.LETTERS -> letters
        KeyboardLayer.SYMBOLS -> symbols
    }

    /** Each row's cell spans, the shape [com.playfieldportal.core.navigation.spanGridMove] walks. */
    fun spans(layer: KeyboardLayer): List<List<Int>> = rows(layer).map { row -> row.map { it.span } }
}
