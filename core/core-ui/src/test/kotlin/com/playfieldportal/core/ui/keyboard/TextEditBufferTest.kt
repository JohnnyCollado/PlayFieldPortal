package com.playfieldportal.core.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** The keyboard's edit model: text plus a caret (Virtual Keyboard plan 6.3). */
class TextEditBufferTest {

    // "a😀b": 😀 is a surrogate pair, so the string has 4 chars: a, high, low, b.
    private val emoji = "a😀b"

    @Test fun `insert at the end appends and advances the caret`() {
        val buffer = TextEditBuffer.atEnd("ab").insert("c")
        assertEquals("abc", buffer.text)
        assertEquals(3, buffer.caret)
    }

    @Test fun `insert mid-text lands at the caret`() {
        val buffer = TextEditBuffer("ac", caret = 1).insert("b")
        assertEquals("abc", buffer.text)
        assertEquals(2, buffer.caret)
    }

    @Test fun `backspace deletes the character before the caret, and nothing at the start`() {
        assertEquals(TextEditBuffer("ac", 1), TextEditBuffer("abc", 2).backspace())
        assertEquals(TextEditBuffer("abc", 0), TextEditBuffer("abc", 0).backspace())
    }

    @Test fun `backspace removes a whole surrogate pair`() {
        assertEquals(TextEditBuffer("ab", 1), TextEditBuffer(emoji, 3).backspace())
    }

    @Test fun `caret moves clamp to the text and never stop inside a surrogate pair`() {
        assertEquals(0, TextEditBuffer("ab", 0).caretLeft().caret)
        assertEquals(2, TextEditBuffer("ab", 2).caretRight().caret)
        assertEquals(3, TextEditBuffer(emoji, 1).caretRight().caret)
        assertEquals(1, TextEditBuffer(emoji, 3).caretLeft().caret)
    }

    @Test fun `an insert past the max length changes nothing`() {
        val full = TextEditBuffer("abc", 3, maxLength = 3)
        assertEquals(full, full.insert("d"))
        assertEquals("abcd", TextEditBuffer("abc", 3, maxLength = 4).insert("d").text)
    }

    @Test fun `replacing the text from outside clamps the caret`() {
        val buffer = TextEditBuffer("abcdef", 6).replaceText("ab")
        assertEquals("ab", buffer.text)
        assertEquals(2, buffer.caret)
        assertEquals(1, TextEditBuffer("abcdef", 1).replaceText("xyz").caret)
    }
}
