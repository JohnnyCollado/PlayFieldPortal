package com.playfieldportal.core.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the WCAG-ish contrast helpers behind [deriveStorefrontColors] — the floor that keeps pale
 * accents (Silver Mono, Golden Amber) from washing white text out. Pure color math, no Compose
 * runtime, so these run as plain JVM unit tests.
 */
class StorefrontColorsTest {

    private fun color(hex: Long) = Color(hex)

    // ── Relative luminance / ratio ────────────────────────────────────────

    @Test
    fun `black and white anchor the luminance range`() {
        assertEquals(0.0, relativeLuminance(Color.Black), 0.0001)
        assertEquals(1.0, relativeLuminance(Color.White), 0.0001)
    }

    @Test
    fun `white on black is the maximum contrast ratio`() {
        assertEquals(21.0, contrastRatio(Color.White, Color.Black), 0.01)
    }

    @Test
    fun `contrast ratio is symmetric`() {
        val a = color(0xFF128BC9)  // classic PSP mid-blue
        val b = color(0xFFE0A32E)  // golden amber
        assertEquals(contrastRatio(a, b), contrastRatio(b, a), 0.0001)
    }

    @Test
    fun `mid blue keeps a modest ratio with white`() {
        // ~3.9:1 — below WCAG AA normal text, which is exactly why derive passes a 3.0 floor
        // rather than flipping the whole classic PSP look to dark text.
        val ratio = contrastRatio(Color.White, color(0xFF128BC9))
        assertTrue("expected mid-blue/white ratio between 3.0 and 4.5, was $ratio", ratio in 3.0..4.5)
    }

    // ── ensureReadable ─────────────────────────────────────────────────────

    @Test
    fun `readable foreground passes through unchanged`() {
        assertEquals(Color.White, ensureReadable(Color.White, color(0xFF003369)))
        assertEquals(Color.Black, ensureReadable(Color.Black, color(0xFFE0A32E)))
    }

    @Test
    fun `white washes out on pale silver and flips to black`() {
        // Silver Mono's brighter anchor (wave #B8C4D0 lightened) — white on it is ~1.5:1.
        val pale = color(0xFFCCD5DD)
        assertEquals(Color.Black, ensureReadable(Color.White, pale))
    }

    @Test
    fun `white washes out on golden amber and flips to black`() {
        // Golden Amber's grid midtone (~#E9BD69) — white is ~1.8:1, black is ~10:1.
        assertEquals(Color.Black, ensureReadable(Color.White, color(0xFFE9BD69)))
    }

    @Test
    fun `classic PSP blue keeps white text at the drawer 3-to-1 floor`() {
        // The derive call passes 3.0 so the established white-on-blue identity survives…
        assertEquals(Color.White, ensureReadable(Color.White, color(0xFF128BC9), 3.0f))
        // …while the helper's own WCAG AA default still flags the same pair as unreadable and
        // picks the pole with the better ratio (black beats white there).
        assertEquals(Color.Black, ensureReadable(Color.White, color(0xFF128BC9)))
    }

    @Test
    fun `flipped text still clears the floor it was chosen against`() {
        for (bgHex in listOf(0xFFCCD5DDL, 0xFFE9BD69L, 0xFF128BC9L)) {
            val bg = color(bgHex)
            val fg = ensureReadable(Color.White, bg, 3.0f)
            assertTrue("$fg on ${bgHex.toString(16)} must clear 3.0:1",
                contrastRatio(fg, bg) >= 3.0)
        }
    }
}
