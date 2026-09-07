package com.playfieldportal.core.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 */
@Composable
fun CustomIconSurface(
    icon: CustomIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    if (icon is CustomIcon.Animated && LocalIconAnimating.current) {
        AsyncImage(
            model = icon.path,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    } else {
        OverrideGlyphSurface(
            bitmap = icon.firstFrame,
            contentDescription = contentDescription,
            modifier = modifier,
        )
    }
}
