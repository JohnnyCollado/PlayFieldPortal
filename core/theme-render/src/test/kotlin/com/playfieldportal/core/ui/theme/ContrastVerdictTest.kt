package com.playfieldportal.core.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the contrast reading the colour pickers show: a ratio, and a verdict in words so the reading
 * never depends on telling a warning colour from a normal one.
 */
class ContrastVerdictTest {

    @Test
    fun `the verdict follows the body and large text thresholds`() {
        assertEquals(ContrastVerdict.EASY, ContrastVerdict.of(4.5f))
        assertEquals(ContrastVerdict.READABLE, ContrastVerdict.of(4.49f))
        assertEquals(ContrastVerdict.READABLE, ContrastVerdict.of(3.0f))
        assertEquals(ContrastVerdict.HARD, ContrastVerdict.of(2.99f))
    }

    @Test
    fun `the worst backdrop decides the reading`() {
        val dark = Color(0xFF101010)
        val pale = Color(0xFFDDDDDD)
        val worst = worstContrast(Color.White, listOf(dark, pale))
        assertEquals(contrastRatio(Color.White, pale).toFloat(), worst, 0.0001f)
    }

    @Test
    fun `the reading is a ratio and a verdict in words`() {
        assertEquals("3.9:1 · Readable", contrastReadout(3.92f))
        assertEquals("8.6:1 · Easy to read", contrastReadout(8.55f))
        assertEquals("1.5:1 · Hard to read", contrastReadout(1.5f))
    }
}
