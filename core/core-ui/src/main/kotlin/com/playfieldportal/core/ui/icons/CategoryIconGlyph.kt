package com.playfieldportal.core.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.domain.model.IconLegibilityStyle

/** Renders the category icon for [iconKey] from its individual drawable (no sprite sheet, no
 *  Canvas). The catalog art is white/monochrome silhouettes, rendered through [PortalIcon] so
 *  the theme's unified icon color applies (white default = visually unchanged). Unknown keys
 *  fall back to the games glyph. Size via [modifier].
 *
 *  Crossbar glyphs are themeable icon slots: when the applied theme carries a custom icon
 *  for this slot, that art renders as-authored instead (console art has no slot) — but the
 *  configured icon-legibility matte still draws behind it, built from the bitmap's own
 *  alpha, via [OverrideGlyphSurface]. */
@Composable
fun CategoryIconGlyph(
    iconKey: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val override = catbarSlotKeyFor(iconKey)?.let { LocalXmbIconOverrides.current[it] }
    if (override != null) {
        OverrideGlyphSurface(
            bitmap = override,
            contentDescription = contentDescription,
            modifier = modifier,
        )
        return
    }
    PortalIcon(
        painter = painterResource(categoryIconFor(iconKey).resId),
        contentDescription = contentDescription,
        modifier = modifier,
    )
}

/**
 * The theme-override half of the matte treatment: a decoded `.pfptheme` bitmap with the
 * configured matte behind it. The bitmap draws untinted (as-authored — the matte never
 * recolors custom art; it only borrows the bitmap's alpha for its shape), so the glyph color
 * handed to [IconMatteSurface] is null.
 *
 * Public because the item column's inline override branches (memory-card art, the settings
 * wrench) live in feature-xmb and need the same treatment as [CategoryIconGlyph].
 */
@Composable
fun OverrideGlyphSurface(
    bitmap: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val style = LocalIconLegibility.current
    val matte = matteColorFor(style, DEFAULT_OVERRIDE_GLYPH_COLOR)
    if (matte == null) {
        // NONE — today's rendering, unchanged.
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier,
        )
        return
    }
    val radiusPx = with(LocalDensity.current) {
        (if (style == IconLegibilityStyle.OFFSET_SHADOW) SHADOW_OFFSET_DP else CONTOUR_RADIUS_DP).dp.toPx()
    }
    IconMatteSurface(
        painter = BitmapPainter(bitmap),
        contentDescription = contentDescription,
        matteColor = matte,
        glyphColor = null,
        offsets = matteOffsets(style),
        radiusPx = radiusPx,
        modifier = modifier,
    )
}

// Luminance input for the override branch's matteColorFor call: theme bitmaps are full-color
// art whose own colors vary, so there is no single "glyph color" — CONTOUR_AUTO therefore
// resolves through this neutral mid-gray, which returns the dark matte. Auto is documented
// as a silhouette-art option; for full-color art the explicit Dark/Light styles are the
// honest picks. (No tone-mapping of the bitmap: that would recolor it.)
private val DEFAULT_OVERRIDE_GLYPH_COLOR = Color(0xFF9E9E9E)
