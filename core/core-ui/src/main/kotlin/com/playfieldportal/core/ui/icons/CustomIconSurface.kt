package com.playfieldportal.core.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale

/**
 * The one draw node for a [CustomIcon] — every override tier funnels here.
 *
 * - Unfocused (or still): draws [CustomIcon.firstFrame] through [OverrideGlyphSurface] — the
 *   icon-legibility matte applies, exactly as theme icons draw today.
 * - Focused + animated: streams the GIF through the global Coil loader (AnimatedImageDecoder).
 *
 * The matte asymmetry is DELIBERATE, not an oversight: the legibility matte applies to the
 * still frame but not to a playing animation. Deriving a matte per GIF frame would re-run the
 * alpha-offset pass every frame inside a LazyColumn, and the focused icon is the one least in
 * need of legibility help.
 *
 * The single-frame-GIF case never reaches the Coil branch: the store classifies those as
 * [CustomIcon.Still] at import, so no decoder is ever started for them.
 *
 * [colorFilter] is a state treatment, not a tint: custom art still renders as authored, but a
 * caller that greys out a locked item (a Shiba coin not yet earned) needs that to reach custom art
 * too. It is applied as one layer over whichever branch draws, so the still frame, its matte and a
 * playing GIF all take it alike.
 */
@Composable
fun CustomIconSurface(
    icon: CustomIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    colorFilter: ColorFilter? = null,
) {
    val drawn = if (colorFilter == null) modifier else modifier.colorFilterLayer(colorFilter)
    if (icon is CustomIcon.Animated && LocalIconAnimating.current) {
        AsyncImage(
            model = icon.path,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = drawn,
        )
    } else {
        OverrideGlyphSurface(
            bitmap = icon.firstFrame,
            contentDescription = contentDescription,
            modifier = drawn,
        )
    }
}

/** Draws the content into an offscreen layer whose paint carries [filter]. */
private fun Modifier.colorFilterLayer(filter: ColorFilter): Modifier = drawWithContent {
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint().apply { colorFilter = filter })
        drawContent()
        canvas.restore()
    }
}
