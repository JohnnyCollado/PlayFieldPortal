package com.playfieldportal.core.ui.icons

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
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
    Image(
        painter = painter,
        contentDescription = contentDescription,
        contentScale = contentScale,
        colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn),
        modifier = modifier,
    )
}
