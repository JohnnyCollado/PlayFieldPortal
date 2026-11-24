package com.playfieldportal.core.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The approved key layout (Virtual Keyboard plan section 4, tests 6.4). */
class VirtualKeyboardLayoutTest {

    private fun chars(layer: KeyboardLayer, row: Int): String =
        VirtualKeyboardLayout.rows(layer)[row].joinToString("") { (it as VirtualKey.Character).char.toString() }

    @Test fun `each layer has four rows of ten columns`() {
        KeyboardLayer.entries.forEach { layer ->
            val rows = VirtualKeyboardLayout.rows(layer)
            assertEquals(4, rows.size)
            rows.forEach { row -> assertEquals(layer.name, 10, row.sumOf { it.span }) }
        }
    }

    @Test fun `the letters layer is Gboard's, digits on the symbols page`() {
        assertEquals("qwertyuiop", chars(KeyboardLayer.LETTERS, 0))
        assertEquals("asdfghjkl-", chars(KeyboardLayer.LETTERS, 1))
        assertEquals("zxcvbnm_.@", chars(KeyboardLayer.LETTERS, 2))
        assertEquals(
            listOf(VirtualKey.Shift(enabled = true), VirtualKey.LayerSwitch("?123"), VirtualKey.Space, VirtualKey.Backspace, VirtualKey.Done),
            VirtualKeyboardLayout.rows(KeyboardLayer.LETTERS)[3],
        )
        assertEquals(listOf(1, 2, 4, 1, 2), VirtualKeyboardLayout.rows(KeyboardLayer.LETTERS)[3].map { it.span })
    }

    @Test fun `the symbols layer is the digits and the common twenty`() {
        assertEquals("1234567890", chars(KeyboardLayer.SYMBOLS, 0))
        assertEquals("@#$%&*()-+", chars(KeyboardLayer.SYMBOLS, 1))
        assertEquals("!?'\",.:;_/", chars(KeyboardLayer.SYMBOLS, 2))
    }

    @Test fun `the bottom row differs between layers only in the layer label and Shift's enabled flag`() {
        val letters = VirtualKeyboardLayout.rows(KeyboardLayer.LETTERS)[3]
        val symbols = VirtualKeyboardLayout.rows(KeyboardLayer.SYMBOLS)[3]
        assertEquals(VirtualKey.Shift(enabled = false), symbols[0])
        assertEquals(VirtualKey.LayerSwitch("ABC"), symbols[1])
        assertEquals(letters.drop(2), symbols.drop(2))
        assertTrue((letters[0] as VirtualKey.Shift).enabled)
        assertFalse((symbols[0] as VirtualKey.Shift).enabled)
    }

    @Test fun `no character appears twice within a layer`() {
        KeyboardLayer.entries.forEach { layer ->
            val all = VirtualKeyboardLayout.rows(layer).flatten().filterIsInstance<VirtualKey.Character>().map { it.char }
            assertEquals(layer.name, all.size, all.toSet().size)
        }
    }
}
