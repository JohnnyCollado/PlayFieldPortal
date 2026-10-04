package com.playfieldportal.core.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp

/**
 * The visual constants the launcher's screens are drawn with and the Theme Studio's preview draws
 * again — declared once, so a tweak on one side cannot leave the other behind.
 */
object ThemeTokens {

    /** The standard directional drop shadow: XMB rows, the context menu, the hint bar. */
    val TextShadow = Shadow(color = Color.Black.copy(alpha = 0.75f), offset = Offset(0f, 2f), blurRadius = 4f)

    /** The detail pages' text shadow: the standard one, a touch lighter. */
    val DetailTextShadow = Shadow(color = Color.Black.copy(alpha = 0.72f), offset = Offset(0f, 2f), blurRadius = 4f)

    /** Unselected XMB labels (the crossbar and the item rows), and the inactive text role. */
    val XmbInactiveLabel = Color(0xCCD8E6FF)

    /** XMB row subtitles. */
    val XmbSecondaryLabel = Color(0xAAC8DAF2)

    /** The legibility scrim over a still wallpaper. */
    val WallpaperScrim = Color(0x59000000)
}

/**
 * The shadow under a menu label of [fill]: the standard one, dimmed with the fill. Compose draws a
 * text shadow at its own alpha whatever the letters' is, so a 45% group header under the
 * full-strength shadow was darker behind than in front and read as a smudge.
 */
fun menuTextShadowFor(fill: Color): Shadow =
    ThemeTokens.TextShadow.copy(color = ThemeTokens.TextShadow.color.copy(alpha = ThemeTokens.TextShadow.color.alpha * fill.alpha))

/** Fill behind the focused menu row: the accent blended toward white, so a dark accent still reads on a dark panel. */
fun menuCursorFillFor(accent: Color): Color = lerp(accent, Color.White, 0.20f).copy(alpha = 0.34f)

/** Bright edge of the focused menu row — the part that makes the cursor unmistakable. */
fun menuCursorEdgeFor(accent: Color): Color = lerp(accent, Color.White, 0.55f).copy(alpha = 0.95f)
