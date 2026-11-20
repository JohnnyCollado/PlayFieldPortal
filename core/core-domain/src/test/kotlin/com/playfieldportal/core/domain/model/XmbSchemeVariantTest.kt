package com.playfieldportal.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins the tint and depth steps a colour preset can be nudged by. The steps exist so a shade can be
 * chosen by word and by a full-screen preview rather than by judging a small swatch, so each step
 * has to be a real, ordered change — and Standard has to be the preset untouched.
 */
class XmbSchemeVariantTest {

    private val royalPurple = 0xFF7A4DD6L
    private val charcoal = 0xFF4A505AL

    private fun hsv(argb: Long) = ColorMath.argbToHsv(argb)

    @Test
    fun `standard leaves every preset exactly as it was`() {
        for (scheme in XmbColorScheme.entries) {
            assertEquals(scheme.resolve(10), scheme.resolve(10, XmbSchemeVariant.STANDARD), "$scheme")
        }
    }

    @Test
    fun `a negative tint moves purple toward blue and a positive one toward pink`() {
        val base = hsv(royalPurple).first
        val bluer = hsv(XmbSchemeVariant(tint = -1).applyTo(royalPurple)).first
        val pinker = hsv(XmbSchemeVariant(tint = 1).applyTo(royalPurple)).first
        assertTrue(bluer < base, "bluer hue $bluer should sit below $base")
        assertTrue(pinker > base, "pinker hue $pinker should sit above $base")
    }

    @Test
    fun `the five tints of a preset are five different colours`() {
        for (scheme in XmbColorScheme.entries.filter { it.supportsVariants }) {
            val wave = scheme.resolve(1).waveColor
            val tints = (-2..2).map { XmbSchemeVariant(tint = it).applyTo(wave) }
            assertEquals(5, tints.toSet().size, "$scheme")
        }
    }

    @Test
    fun `depth runs from lighter to deeper in order`() {
        for (scheme in XmbColorScheme.entries.filter { it.supportsVariants }) {
            val wave = scheme.resolve(1).waveColor
            val brightness = (-2..2).map { hsv(XmbSchemeVariant(depth = it).applyTo(wave)).third }
            assertEquals(brightness.sortedDescending(), brightness, "$scheme")
            assertNotEquals(brightness.first(), brightness.last(), "$scheme")
        }
    }

    @Test
    fun `a grey gets cooler and warmer rather than a hue shift`() {
        val (_, baseSaturation, _) = hsv(charcoal)
        val (coolHue, coolSaturation, _) = hsv(XmbSchemeVariant(tint = -2).applyTo(charcoal))
        val (warmHue, _, _) = hsv(XmbSchemeVariant(tint = 2).applyTo(charcoal))
        assertTrue(coolHue in 180f..260f, "cooler stays blue, was $coolHue")
        assertTrue(coolSaturation > baseSaturation, "cooler is a stronger blue")
        assertTrue(warmHue < 60f, "much warmer crosses to a warm hue, was $warmHue")
    }

    @Test
    fun `the monthly scheme ignores a variant`() {
        assertFalse(XmbColorScheme.ORIGINAL.supportsVariants)
        assertEquals(
            XmbColorScheme.ORIGINAL.resolve(6),
            XmbColorScheme.ORIGINAL.resolve(6, XmbSchemeVariant(tint = 2, depth = -2)),
        )
    }

    @Test
    fun `the background gradient follows the stepped colour`() {
        val variant = XmbSchemeVariant(tint = -1, depth = 1)
        val palette = XmbColorScheme.ROYAL_PURPLE.resolve(1, variant)
        assertEquals(variant.applyTo(royalPurple), palette.waveColor)
        assertEquals(lightBackgroundAnchors(palette.waveColor), palette.backgroundTop to palette.backgroundBottom)
    }

    @Test
    fun `a variant survives being stored and read back`() {
        for (tint in -2..2) for (depth in -2..2) {
            val variant = XmbSchemeVariant(tint, depth)
            assertEquals(variant, XmbSchemeVariant.decode(variant.encode()))
        }
    }

    @Test
    fun `a missing or mangled stored variant reads as standard`() {
        for (text in listOf(null, "", "1", "a,b", "1,2,3", " , ")) {
            assertEquals(XmbSchemeVariant.STANDARD, XmbSchemeVariant.decode(text), "'$text'")
        }
    }

    @Test
    fun `steps stop at two either way`() {
        assertEquals(XmbSchemeVariant(2, -2), XmbSchemeVariant.of(9, -9))
        assertEquals(XmbSchemeVariant(2, -2), XmbSchemeVariant.decode("9,-9"))
        assertEquals(XmbSchemeVariant(tint = 2), XmbSchemeVariant(tint = 2).withTint(1))
        assertEquals(XmbSchemeVariant(depth = -1), XmbSchemeVariant(depth = -2).withDepth(1))
    }
}
