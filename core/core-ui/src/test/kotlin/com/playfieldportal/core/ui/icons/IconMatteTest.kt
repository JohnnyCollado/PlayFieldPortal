package com.playfieldportal.core.ui.icons

import androidx.compose.ui.graphics.Color
import com.playfieldportal.core.domain.model.IconLegibilityStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Pins the matte contract ([matteOffsets] / [matteColorFor]) and the tolerant preference parse
 * ([IconLegibilityStyle.fromName]). The CONTOUR_AUTO cases are the defect this whole feature
 * exists to avoid: the theme's iconColor is not always light, so a hardcoded dark matte behind
 * the owner's dark red glyphs on a dark sky would add nothing — Auto must pick the luminance
 * OPPOSITE of the glyph.
 */
class IconMatteTest {

    private val darkGlyph = Color(0xFFD52E1F)   // the owner's theme iconColor from the reference frame
    private val lightGlyph = Color(0xFFFFFFFF)

    @Test
    fun `NONE draws no matte at all`() {
        assertTrue(matteOffsets(IconLegibilityStyle.NONE).isEmpty())
        assertNull(matteColorFor(IconLegibilityStyle.NONE, darkGlyph))
        assertNull(matteColorFor(IconLegibilityStyle.NONE, lightGlyph))
    }

    @Test
    fun `OFFSET_SHADOW has exactly one offset and every contour style has eight`() {
        assertEquals(1, matteOffsets(IconLegibilityStyle.OFFSET_SHADOW).size)
        listOf(
            IconLegibilityStyle.CONTOUR_DARK,
            IconLegibilityStyle.CONTOUR_LIGHT,
            IconLegibilityStyle.CONTOUR_AUTO,
        ).forEach { style ->
            assertEquals("expected 8 contour offsets for $style", 8, matteOffsets(style).size)
        }
    }

    @Test
    fun `contour offsets are unit-length so diagonals read as a round contour`() {
        // Catches a diagonal written as Offset(1f, 1f) (√2 long — a square, not a circle).
        val epsilon = 1e-4f
        listOf(
            IconLegibilityStyle.CONTOUR_DARK,
            IconLegibilityStyle.CONTOUR_LIGHT,
            IconLegibilityStyle.CONTOUR_AUTO,
        ).forEach { style ->
            matteOffsets(style).forEach { o ->
                assertEquals(
                    "offset $o for $style must be unit-length",
                    1f,
                    sqrt(o.x * o.x + o.y * o.y),
                    epsilon,
                )
            }
        }
    }

    @Test
    fun `OFFSET_SHADOW offsets down and right`() {
        val only = matteOffsets(IconLegibilityStyle.OFFSET_SHADOW).single()
        assertTrue("shadow must offset right", only.x > 0f)
        assertTrue("shadow must offset down", only.y > 0f)
    }

    @Test
    fun `AUTO gives a dark glyph the light matte - the motivating frame`() {
        assertEquals(
            MatteLight.copy(alpha = CONTOUR_MATTE_ALPHA),
            matteColorFor(IconLegibilityStyle.CONTOUR_AUTO, darkGlyph),
        )
    }

    @Test
    fun `AUTO gives a light glyph the dark matte`() {
        assertEquals(
            MatteDark.copy(alpha = CONTOUR_MATTE_ALPHA),
            matteColorFor(IconLegibilityStyle.CONTOUR_AUTO, lightGlyph),
        )
    }

    @Test
    fun `AUTO agrees with the explicit picks on either side of the threshold`() {
        // Dark glyph → light matte → matches CONTOUR_LIGHT; light glyph → dark matte → matches
        // CONTOUR_DARK. If these ever disagree, Auto and the explicit styles have drifted.
        assertEquals(
            matteColorFor(IconLegibilityStyle.CONTOUR_LIGHT, darkGlyph),
            matteColorFor(IconLegibilityStyle.CONTOUR_AUTO, darkGlyph),
        )
        assertEquals(
            matteColorFor(IconLegibilityStyle.CONTOUR_DARK, lightGlyph),
            matteColorFor(IconLegibilityStyle.CONTOUR_AUTO, lightGlyph),
        )
    }

    @Test
    fun `explicit styles apply the contour alpha, shadow applies its own`() {
        assertEquals(MatteDark.copy(alpha = CONTOUR_MATTE_ALPHA), matteColorFor(IconLegibilityStyle.CONTOUR_DARK, lightGlyph))
        assertEquals(MatteLight.copy(alpha = CONTOUR_MATTE_ALPHA), matteColorFor(IconLegibilityStyle.CONTOUR_LIGHT, darkGlyph))
        assertEquals(MatteDark.copy(alpha = SHADOW_MATTE_ALPHA), matteColorFor(IconLegibilityStyle.OFFSET_SHADOW, darkGlyph))
        assertEquals(MatteDark.copy(alpha = SHADOW_MATTE_ALPHA), matteColorFor(IconLegibilityStyle.OFFSET_SHADOW, lightGlyph))
    }

    @Test
    fun `fromName round-trips every constant`() {
        IconLegibilityStyle.entries.forEach { style ->
            assertEquals(style, IconLegibilityStyle.fromName(style.name))
        }
    }

    @Test
    fun `fromName falls back to NONE for null blank and stale values`() {
        assertEquals(IconLegibilityStyle.NONE, IconLegibilityStyle.fromName(null))
        assertEquals(IconLegibilityStyle.NONE, IconLegibilityStyle.fromName(""))
        assertEquals(IconLegibilityStyle.NONE, IconLegibilityStyle.fromName("CONTOUR_MEDIUM"))
    }
}
