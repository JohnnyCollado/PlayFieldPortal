package com.playfieldportal.themekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccentDeriverTest {

    private fun image(width: Int = 60, height: Int = 30, argbAt: (Int, Int) -> Int): BmpImage {
        val px = IntArray(width * height) { i -> argbAt(i % width, i / width) }
        return BmpImage(width, height, px)
    }

    private fun hueOf(argb: Int): Float {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val v = maxOf(r, g, b); val min = minOf(r, g, b); val d = v - min
        if (d == 0f) return 0f
        val h = when (v) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } / 6f
        return if (h < 0) h + 1f else h
    }

    @Test
    fun `dominant hue wins - pink wallpaper yields pink accent`() {
        // Soft pink field (classypink-like) with a gray minority.
        val img = image { x, _ -> if (x < 45) 0xFFF4B6D2.toInt() else 0xFF808080.toInt() }
        val accent = assertNotNull(AccentDeriver.deriveAccent(img))
        val hue = hueOf(accent)
        assertTrue(hue > 0.85f || hue < 0.03f, "expected pink-ish hue, got $hue (#${Integer.toHexString(accent)})")
    }

    @Test
    fun `red wallpaper yields red accent`() {
        val img = image { _, _ -> 0xFFB03040.toInt() }
        val accent = assertNotNull(AccentDeriver.deriveAccent(img))
        val hue = hueOf(accent)
        assertTrue(hue < 0.05f || hue > 0.95f, "expected red hue, got $hue")
    }

    @Test
    fun `pastel source is boosted to a usable accent strength`() {
        val img = image { _, _ -> 0xFFF4B6D2.toInt() } // pale pink: s≈0.25, v≈0.96
        val accent = assertNotNull(AccentDeriver.deriveAccent(img))
        val r = accent shr 16 and 0xFF; val g = accent shr 8 and 0xFF; val b = accent and 0xFF
        val v = maxOf(r, g, b) / 255f
        val s = if (v == 0f) 0f else (maxOf(r, g, b) - minOf(r, g, b)).toFloat() / maxOf(r, g, b)
        // Floors are applied in HSV; the 8-bit RGB round-trip can shave ~0.005 off.
        assertTrue(s >= 0.54f, "saturation should be boosted to ~0.55, got $s")
        assertTrue(v >= 0.84f, "value should be boosted to ~0.85, got $v")
    }

    @Test
    fun `grayscale wallpaper yields null so callers fall back to a preset`() {
        val img = image { x, y -> val g = (x * 4 + y) and 0xFF; (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
        assertNull(AccentDeriver.deriveAccent(img))
    }

    @Test
    fun `alpha channel of result is opaque`() {
        val img = image { _, _ -> 0xFF3A7BD5.toInt() }
        val accent = assertNotNull(AccentDeriver.deriveAccent(img))
        assertEquals(0xFF, accent ushr 24)
    }
}
