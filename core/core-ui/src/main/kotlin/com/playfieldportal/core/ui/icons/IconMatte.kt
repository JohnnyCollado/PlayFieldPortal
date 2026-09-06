package com.playfieldportal.core.ui.icons

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.playfieldportal.core.domain.model.IconLegibilityStyle
import kotlin.math.sqrt

// Tuning constants, measured (not guessed) from the approved 4 px contour / 3 px offset preview
// on a 1080x1920 frame at density 2.306 — converted once to dp so they hold on every density.
// Internal: the drawing code reads them with LocalDensity; the unit tests read the unit-space
// geometry through matteOffsets, which must stay density-free.
internal val CONTOUR_RADIUS_DP = 1.75f  // ≈ 4.0 px on the reference device
internal val SHADOW_OFFSET_DP = 1.25f   // ≈ 2.9 px, down and to the right

// Matte alphas: the contour needs a near-solid matte to read as one continuous outline; the
// single offset copy sits closer to the glyph and reads with a little translucency.
internal const val CONTOUR_MATTE_ALPHA = 0.95f
internal const val SHADOW_MATTE_ALPHA = 0.85f

/**
 * The two matte colors, named constants so [IconLegibilityStyle.CONTOUR_AUTO] can never disagree
 * with an explicit [IconLegibilityStyle.CONTOUR_DARK] / [IconLegibilityStyle.CONTOUR_LIGHT] pick:
 * Auto returns exactly one of these two, never a derived third color.
 *
 * Both sit just off pure white/black (a faint blue cast) so a matte touching the glyph's own
 * color still separates — pure black behind a near-black glyph vanishes at the contour seam.
 */
val MatteLight = Color(0xFFEBF5FF)
val MatteDark = Color(0xFF000A12)

/**
 * Matte offsets in units of the radius — the contour radius for the contour styles, the shadow
 * offset for [IconLegibilityStyle.OFFSET_SHADOW] (the two constants are distinct, so the unit
 * space is per-style). Empty when the style draws no matte: callers must treat that as "draw
 * exactly what ships today", NOT "draw a matte at alpha 0" — an empty draw still costs.
 *
 * The contour styles return the 8 compass directions (diagonals at 1/√2 so the contour is
 * round rather than square); the offset shadow returns one down-right copy.
 */
fun matteOffsets(style: IconLegibilityStyle): List<Offset> = when (style) {
    IconLegibilityStyle.NONE -> emptyList()
    IconLegibilityStyle.OFFSET_SHADOW -> listOf(Offset(1f, 1f))
    IconLegibilityStyle.CONTOUR_DARK,
    IconLegibilityStyle.CONTOUR_LIGHT,
    IconLegibilityStyle.CONTOUR_AUTO -> CONTOUR_OFFSETS
}

private val CONTOUR_OFFSETS: List<Offset> = run {
    val diagonal = 1f / sqrt(2f)
    listOf(
        Offset(0f, -1f),               // N
        Offset(diagonal, -diagonal),   // NE
        Offset(1f, 0f),                // E
        Offset(diagonal, diagonal),    // SE
        Offset(0f, 1f),                // S
        Offset(-diagonal, diagonal),   // SW
        Offset(-1f, 0f),               // W
        Offset(-diagonal, -diagonal),  // NW
    )
}

/**
 * The final matte color (alpha applied) for [style] behind a glyph of [glyphColor]; null when
 * the style draws no matte. [IconLegibilityStyle.CONTOUR_AUTO] picks by the glyph's luminance —
 * the theme's `iconColor` is not always light, so the matte must be the luminance opposite:
 * a dark glyph gets the light matte, a light glyph the dark one.
 */
fun matteColorFor(style: IconLegibilityStyle, glyphColor: Color): Color? = when (style) {
    IconLegibilityStyle.NONE -> null
    IconLegibilityStyle.OFFSET_SHADOW -> MatteDark.copy(alpha = SHADOW_MATTE_ALPHA)
    IconLegibilityStyle.CONTOUR_DARK -> MatteDark.copy(alpha = CONTOUR_MATTE_ALPHA)
    IconLegibilityStyle.CONTOUR_LIGHT -> MatteLight.copy(alpha = CONTOUR_MATTE_ALPHA)
    IconLegibilityStyle.CONTOUR_AUTO ->
        if (glyphColor.luminance() < AUTO_LUMINANCE_THRESHOLD) {
            MatteLight.copy(alpha = CONTOUR_MATTE_ALPHA)
        } else {
            MatteDark.copy(alpha = CONTOUR_MATTE_ALPHA)
        }
}

// Luminance at or above which a glyph counts as light (gets the dark matte). Mid-gray and up.
private const val AUTO_LUMINANCE_THRESHOLD = 0.5f

/**
 * The ambient icon-legibility treatment for the whole XMB. `staticCompositionLocalOf` matches
 * [LocalXmbIconOverrides]: the value changes rarely (a settings cycle), so a full subtree
 * recomposition on change is the right trade over per-read bookkeeping.
 */
val LocalIconLegibility = staticCompositionLocalOf { IconLegibilityStyle.DEFAULT }
