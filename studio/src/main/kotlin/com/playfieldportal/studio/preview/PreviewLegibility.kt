package com.playfieldportal.studio.preview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.playfieldportal.themekit.ThemeLegibility
import kotlin.math.sqrt

/*
 * The theme's legibility settings as render parameters for the preview. Icons mirror core-ui's
 * IconMatte.kt (offsets, radius, matte colours and alphas, all by name below). Text approximates
 * TextLegibilityStyle: the launcher resolves AUTO and PLATE against a measured backdrop, which the
 * Studio has no equivalent of, so AUTO shows the always-on shadow floor and PLATE a fixed alpha.
 */

/** How a label separates from what is behind it ([ThemeLegibility.text]). */
enum class LabelProtection(val hasShadow: Boolean) {
    /** The fill alone. */
    NONE(hasShadow = false),

    /** The launcher's standard drop shadow; also what AUTO shows (the floor it never goes below). */
    SHADOW(hasShadow = true),

    /** A stroked copy behind the fill. */
    OUTLINE(hasShadow = false),

    /** A text-shaped rounded plate of the opposite polarity. */
    PLATE(hasShadow = false),
}

/** How an icon glyph separates from what is behind it ([ThemeLegibility.icon]). */
enum class IconMatteStyle {
    NONE, OFFSET_SHADOW, OFFSET_SHADOW_LIGHT, CONTOUR_DARK, CONTOUR_LIGHT, CONTOUR_AUTO;

    /** The single down-right copy, dark or light, as opposed to a contour all around the glyph. */
    val isOffsetShadow: Boolean get() = this == OFFSET_SHADOW || this == OFFSET_SHADOW_LIGHT
}

data class PreviewLegibility(
    val text: LabelProtection,
    val icon: IconMatteStyle,
    val solidUnfocusedIcons: Boolean,
) {
    /** XMBCategoryBar: "Solid Unfocused Icons" skips the 0.58 dim; selection still reads by size and label. */
    fun categoryIconAlpha(selected: Boolean): Float = if (selected || solidUnfocusedIcons) 1f else UNFOCUSED_ALPHA

    /** IconMatte.matteOffsets: unit offsets scaled by [matteRadiusDp]; empty draws exactly the plain glyph. */
    fun matteOffsets(): List<Offset> = when (icon) {
        IconMatteStyle.NONE -> emptyList()
        IconMatteStyle.OFFSET_SHADOW, IconMatteStyle.OFFSET_SHADOW_LIGHT -> listOf(Offset(1f, 1f))
        IconMatteStyle.CONTOUR_DARK, IconMatteStyle.CONTOUR_LIGHT, IconMatteStyle.CONTOUR_AUTO -> CONTOUR_OFFSETS
    }

    /** The unit [matteOffsets] multiplier: the shadow offset for either offset shadow, the contour radius otherwise. */
    val matteRadiusDp: Float get() = if (icon.isOffsetShadow) SHADOW_OFFSET_DP else CONTOUR_RADIUS_DP

    /** IconMatte.matteColorFor: the matte (alpha applied) behind a glyph of [glyph], null for no matte. */
    fun matteColor(glyph: Color): Color? = when (icon) {
        IconMatteStyle.NONE -> null
        IconMatteStyle.OFFSET_SHADOW -> MATTE_DARK.copy(alpha = SHADOW_MATTE_ALPHA)
        // The light shadow, for dark glyphs a dark shadow disappears into.
        IconMatteStyle.OFFSET_SHADOW_LIGHT -> MATTE_LIGHT.copy(alpha = SHADOW_MATTE_ALPHA)
        IconMatteStyle.CONTOUR_DARK -> MATTE_DARK.copy(alpha = CONTOUR_MATTE_ALPHA)
        IconMatteStyle.CONTOUR_LIGHT -> MATTE_LIGHT.copy(alpha = CONTOUR_MATTE_ALPHA)
        // The matte is the luminance opposite of the glyph: a light glyph gets the dark one.
        IconMatteStyle.CONTOUR_AUTO ->
            if (glyph.luminance() < AUTO_LUMINANCE_THRESHOLD) MATTE_LIGHT.copy(alpha = CONTOUR_MATTE_ALPHA)
            else MATTE_DARK.copy(alpha = CONTOUR_MATTE_ALPHA)
    }

    companion object {
        /** A theme that says nothing: today's shadowed labels and plain icons. */
        val DEFAULT = PreviewLegibility(LabelProtection.SHADOW, IconMatteStyle.NONE, solidUnfocusedIcons = false)

        const val UNFOCUSED_ALPHA = 0.58f

        /** TextLegibility's own example of a solved plate; the Studio has no backdrop to solve against. */
        const val PLATE_ALPHA = 0.38f

        // IconMatte.kt constants.
        private const val CONTOUR_RADIUS_DP = 1.75f
        private const val SHADOW_OFFSET_DP = 1.25f
        private const val CONTOUR_MATTE_ALPHA = 0.95f
        private const val SHADOW_MATTE_ALPHA = 0.85f
        private const val AUTO_LUMINANCE_THRESHOLD = 0.5f
        private val MATTE_LIGHT = Color(0xFFEBF5FF)
        private val MATTE_DARK = Color(0xFF000A12)

        /** The 8 compass directions; diagonals at 1/sqrt(2) so the contour is round rather than square. */
        private val CONTOUR_OFFSETS: List<Offset> = run {
            val d = 1f / sqrt(2f)
            listOf(
                Offset(0f, -1f), Offset(d, -d), Offset(1f, 0f), Offset(d, d),
                Offset(0f, 1f), Offset(-d, d), Offset(-1f, 0f), Offset(-d, -d),
            )
        }

        /** Absent or unknown values fall back to the default, like the launcher's tolerant parse. */
        fun of(legibility: ThemeLegibility?): PreviewLegibility = PreviewLegibility(
            text = when (legibility?.text) {
                "none" -> LabelProtection.NONE
                "outline" -> LabelProtection.OUTLINE
                "plate" -> LabelProtection.PLATE
                else -> LabelProtection.SHADOW // shadow, auto, unset, unknown
            },
            icon = when (legibility?.icon) {
                "offset_shadow" -> IconMatteStyle.OFFSET_SHADOW
                "offset_shadow_light" -> IconMatteStyle.OFFSET_SHADOW_LIGHT
                "contour_dark" -> IconMatteStyle.CONTOUR_DARK
                "contour_light" -> IconMatteStyle.CONTOUR_LIGHT
                "contour_auto" -> IconMatteStyle.CONTOUR_AUTO
                else -> IconMatteStyle.NONE
            },
            solidUnfocusedIcons = legibility?.solidUnfocusedIcons == true,
        )
    }
}
