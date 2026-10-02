package com.playfieldportal.core.domain.model

/**
 * How XMB silhouette glyphs separate from the background — the PSP-style two-layer icon:
 * each glyph draws twice, a matte copy behind the glyph, so the icon hugs its own contour
 * instead of fogging the area around it (the way a blurred halo does).
 *
 * The matte must be the luminance OPPOSITE of the glyph: the theme's `iconColor` is not
 * always light (the motivating frame had a dark red glyph over a near-black sky, where a
 * dark matte adds nothing), which is why [CONTOUR_AUTO] derives the matte from the glyph's
 * own luminance. A hardcoded dark matte behind a dark glyph on dark wallpaper is invisible —
 * exactly the failure this setting exists to fix.
 *
 *  - [NONE]           today's rendering, unchanged — the shipped default.
 *  - [OFFSET_SHADOW]  one dark matte copy, offset down and to the right (a hard drop shadow).
 *  - [OFFSET_SHADOW_LIGHT] the same copy in the light matte, for dark glyphs (a black icon) that
 *                     a dark shadow disappears into.
 *  - [CONTOUR_DARK]   dark matte copies dilated all around the glyph.
 *  - [CONTOUR_LIGHT]  the same, with a light matte.
 *  - [CONTOUR_AUTO]   the same, matte color derived from the glyph's luminance.
 */
enum class IconLegibilityStyle(val label: String) {
    NONE("None"),
    // Stored by name: OFFSET_SHADOW keeps its name from before the light one existed.
    OFFSET_SHADOW("Offset Shadow (Dark)"),
    OFFSET_SHADOW_LIGHT("Offset Shadow (Light)"),
    CONTOUR_DARK("Contour (Dark)"),
    CONTOUR_LIGHT("Contour (Light)"),
    CONTOUR_AUTO("Contour (Auto)");

    /** The single down-right copy, dark or light — as opposed to a contour all around the glyph. */
    val isOffsetShadow: Boolean get() = this == OFFSET_SHADOW || this == OFFSET_SHADOW_LIGHT

    companion object {
        val DEFAULT = NONE

        /** Tolerant parse for the persisted preference; unknown/blank falls back to [DEFAULT]. */
        fun fromName(value: String?): IconLegibilityStyle =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
