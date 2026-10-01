package com.playfieldportal.core.ui.keyboard

/**
 * The text the virtual keyboard edits and its caret. Immutable; every edit returns a new buffer.
 * The caret never rests inside a surrogate pair, and backspace removes a pair whole.
 */
data class TextEditBuffer(
    val text: String,
    val caret: Int,
    val maxLength: Int? = null,
) {
    /** [value] inserted at the caret, or this buffer unchanged when it would pass [maxLength]. */
    fun insert(value: String): TextEditBuffer {
        if (maxLength != null && text.length + value.length > maxLength) return this
        return copy(text = text.substring(0, caret) + value + text.substring(caret), caret = caret + value.length)
    }

    fun backspace(): TextEditBuffer {
        if (caret == 0) return this
        val start = previousBoundary(caret)
        return copy(text = text.removeRange(start, caret), caret = start)
    }

    fun caretLeft(): TextEditBuffer = if (caret == 0) this else copy(caret = previousBoundary(caret))

    fun caretRight(): TextEditBuffer = if (caret == text.length) this else copy(caret = nextBoundary(caret))

    /** The host changed the text underneath the keyboard: keep the caret, clamped to the new text. */
    fun replaceText(newText: String): TextEditBuffer {
        val clamped = caret.coerceIn(0, newText.length)
        val safe = if (splitsPair(newText, clamped)) clamped - 1 else clamped
        return copy(text = newText, caret = safe)
    }

    private fun previousBoundary(index: Int): Int =
        if (index >= 2 && Character.isSurrogatePair(text[index - 2], text[index - 1])) index - 2 else index - 1

    private fun nextBoundary(index: Int): Int =
        if (index + 1 < text.length && Character.isSurrogatePair(text[index], text[index + 1])) index + 2 else index + 1

    companion object {
        /** [text] with the caret after its last character. */
        fun atEnd(text: String, maxLength: Int? = null) = TextEditBuffer(text, text.length, maxLength)

        private fun splitsPair(text: String, index: Int): Boolean =
            index in 1 until text.length && Character.isSurrogatePair(text[index - 1], text[index])
    }
}
