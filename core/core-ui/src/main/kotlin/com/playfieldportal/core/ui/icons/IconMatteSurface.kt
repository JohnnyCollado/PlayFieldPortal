package com.playfieldportal.core.ui.icons

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.domain.model.IconLegibilityStyle

/**
 * The one draw node both matte surfaces share: a Box whose `onDrawBehind` lays the fitted glyph
 * at [offsets] * [radiusPx] in the matte color, then draws the glyph itself at the true origin
 * — glyph last, so the matte never shows through a semi-transparent glyph. The item column is
 * a LazyColumn, so this must stay ONE node (no stacked Image layers — node count multiplies on
 * every scroll).
 *
 * [radiusPx] arrives precomputed (callers convert dp with LocalDensity — never hardcode px).
 *
 * Used by [PortalIcon] (tinted silhouette art) and by the theme-override branches
 * ([CategoryIconGlyph], [ThemedGlyph]) for decoded `.pfptheme` bitmaps. Custom icons still
 * draw as-authored: the matte is built from the bitmap's own alpha and sits BEHIND the
 * untinted art, so the glyph itself is never recolored.
 */
@Composable
internal fun IconMatteSurface(
    painter: Painter,
    contentDescription: String?,
    matteColor: Color,
    glyphColor: Color?,
    offsets: List<Offset>,
    radiusPx: Float,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val matteFilter = ColorFilter.tint(matteColor, BlendMode.SrcIn)
    // glyphColor null = the glyph draws untinted (theme-override bitmaps as-authored). The
    // matte copies still inherit the bitmap's alpha shape: SrcIn keeps the source alpha, so
    // transparent regions of the art produce no matte either.
    val glyphFilter = glyphColor?.let { ColorFilter.tint(it, BlendMode.SrcIn) }

    Box(
        modifier
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .drawWithCache {
                // Fit exactly the way ContentScale would lay the glyph out, so every matte copy
                // sits on the drawn glyph's own contour (catalog art is not all 1:1).
                val factor = contentScale.computeScaleFactor(painter.intrinsicSize, size)
                val dst = Size(
                    painter.intrinsicSize.width * factor.scaleX,
                    painter.intrinsicSize.height * factor.scaleY,
                )
                val origin = Offset((size.width - dst.width) / 2f, (size.height - dst.height) / 2f)
                onDrawBehind {
                    for (o in offsets) {
                        drawTranslated(origin.x + o.x * radiusPx, origin.y + o.y * radiusPx) {
                            with(painter) { draw(dst, colorFilter = matteFilter) }
                        }
                    }
                    drawTranslated(origin.x, origin.y) {
                        with(painter) { draw(dst, colorFilter = glyphFilter) }
                    }
                }
            },
    )
}

private inline fun DrawScope.drawTranslated(dx: Float, dy: Float, block: DrawScope.() -> Unit) {
    translate(dx, dy) { block() }
}

/**
 * The Material-vector half of the matte treatment — the default (non-overridden) branch of
 * [ThemedGlyph]. Renders [vector] tinted to [tint], with the configured matte behind it when
 * an icon-legibility style is active; under [IconLegibilityStyle.NONE] it is exactly a plain
 * material3 `Icon`, so the default setting stays bit-identical. This is what carries the
 * setting into the main XMB item column's Material-glyph rows (music/video/photo cards,
 * section rows, hub rows) — the silhouette-art paths ([PortalIcon], [CategoryIconGlyph])
 * already had it.
 *
 * Call sites must size the icon via [modifier] (every ThemedGlyph call site does): the matte
 * surface draws into the Box's measured size, unlike material3 Icon's intrinsic fallback.
 */
@Composable
internal fun VectorGlyphSurface(
    vector: ImageVector,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val style = LocalIconLegibility.current
    val matte = matteColorFor(style, tint)
    if (matte == null) {
        // NONE — today's rendering, exactly as it shipped.
        Icon(vector, contentDescription = contentDescription, tint = tint, modifier = modifier)
        return
    }
    val radiusPx = with(LocalDensity.current) {
        (if (style == IconLegibilityStyle.OFFSET_SHADOW) SHADOW_OFFSET_DP else CONTOUR_RADIUS_DP).dp.toPx()
    }
    IconMatteSurface(
        painter = rememberVectorPainter(vector),
        contentDescription = contentDescription,
        matteColor = matte,
        glyphColor = tint,
        offsets = matteOffsets(style),
        radiusPx = radiusPx,
        modifier = modifier,
    )
}
