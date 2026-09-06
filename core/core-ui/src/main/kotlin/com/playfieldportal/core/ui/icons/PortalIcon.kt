package com.playfieldportal.core.ui.icons

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.domain.model.IconLegibilityStyle
import com.playfieldportal.core.ui.theme.LocalPFPColors

/**
 * The single entry point for rendering the XMB's own icon art (catbar_* / sysicon_*
 * silhouettes and other single-color glyph drawables), applying the theme's unified
 * [com.playfieldportal.core.ui.theme.PFPColors.iconColor] in ONE place.
 *
 * The tint uses [BlendMode.SrcIn]: the icon's alpha defines the shape and the tint replaces
 * its color — verified to recolor both vector drawables and the raster silhouette PNGs
 * cleanly (docs/icon-system-plan.md). With the default white icon color this is visually
 * identical to untinted rendering, so adopting PortalIcon is a no-op until a theme sets a
 * custom icon color.
 *
 * Icon legibility: when [LocalIconLegibility] is not [IconLegibilityStyle.NONE], a matte copy
 * of the SAME silhouette draws behind the glyph — the PSP-style two-layer icon. The separation
 * comes from a second copy of the same contour, so it hugs the glyph instead of fogging the
 * area around it the way a blurred halo does; the matte color is the luminance OPPOSITE of the
 * glyph color (see [matteColorFor]). The default [IconLegibilityStyle.NONE] renders the plain
 * `Image` below, bit-identical to pre-matte builds.
 *
 * NOT for content imagery (game artwork, album covers, app icons, photos) — those keep
 * their own colors; use Image/AsyncImage directly.
 */
@Composable
fun PortalIcon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalPFPColors.current.iconColor,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val style = LocalIconLegibility.current
    val matte = matteColorFor(style, tint)
    if (matte == null) {
        // NONE — today's rendering, exactly as it shipped. No draw cost added.
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = contentScale,
            colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn),
            modifier = modifier,
        )
        return
    }

    // The contour styles measure in contour radii, the offset shadow in shadow offsets —
    // distinct dp constants, converted once here (never hardcode px).
    val radiusPx = with(LocalDensity.current) {
        (if (style == IconLegibilityStyle.OFFSET_SHADOW) SHADOW_OFFSET_DP else CONTOUR_RADIUS_DP).dp.toPx()
    }
    IconMatteSurface(
        painter = painter,
        contentDescription = contentDescription,
        matteColor = matte,
        glyphColor = tint,
        offsets = matteOffsets(style),
        radiusPx = radiusPx,
        contentScale = contentScale,
        modifier = modifier,
    )
}
