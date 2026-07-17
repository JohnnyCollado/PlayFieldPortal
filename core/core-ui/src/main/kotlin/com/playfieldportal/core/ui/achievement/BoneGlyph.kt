package com.playfieldportal.core.ui.achievement

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The bone emoji, drawn as the prestige glyph. Flattened to a single flat color at render time.
private const val BONE = "🦴" // U+1F9B4 BONE

/**
 * The prestige Bone glyph — one Bone per completed 100-level cycle. Renders the bone emoji but
 * forces it to a flat, monochrome silhouette with a SrcIn color filter, so it tints to any accent
 * instead of showing the multi-color emoji. Used beside the Bone count wherever prestige is shown
 * (the player card, the status view, the XMB player-card row).
 */
@Composable
fun BoneGlyph(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
) {
    // The XMB row redraws every frame over the animated wave — the Paint is remembered (and its
    // text size set once per draw) so drawing never allocates.
    val paint = remember(tint) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            // SrcIn keeps the glyph's shape (alpha) but replaces every color with the tint.
            colorFilter = android.graphics.PorterDuffColorFilter(
                tint.toArgb(),
                android.graphics.PorterDuff.Mode.SRC_IN,
            )
        }
    }
    Canvas(modifier = modifier.size(size)) {
        paint.textSize = this.size.height * 0.92f
        val metrics = paint.fontMetrics
        val baseline = this.size.height / 2f - (metrics.ascent + metrics.descent) / 2f
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawText(BONE, this.size.width / 2f, baseline, paint)
        }
    }
}
