package com.playfieldportal.core.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * The one draw node for a [CustomIcon] — every override tier funnels here.
 *
 * - Still, or not allowed to play (Animated Images): draws [CustomIcon.firstFrame] through
 *   [OverrideGlyphSurface] — the icon-legibility matte applies, exactly as theme icons draw.
 * - Animated and allowed to play: streams the GIF through ArtworkImage, which also holds it
 *   still while it is off screen.
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
    // Animated Images decides whether this icon could play for its focus; ArtworkImage then holds
    // it still while it is off screen. An icon that could never play right now skips the decoder
    // and draws its stored first frame.
    val focused = com.playfieldportal.core.ui.motion.LocalIconFocused.current
    val mayPlay = com.playfieldportal.core.ui.motion.LocalImageMotion.current.shouldAnimate(
        onScreen = true,
        focused = focused,
        allowed = com.playfieldportal.core.ui.motion.LocalMotionAllowed.current,
    )
    if (icon is CustomIcon.Animated && mayPlay) {
        com.playfieldportal.core.ui.motion.ArtworkImage(
            model = icon.path,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier,
            focused = focused,
        )
    } else {
        OverrideGlyphSurface(
            bitmap = icon.firstFrame,
            contentDescription = contentDescription,
            modifier = modifier,
        )
    }
}
