package com.playfieldportal.feature.xmb.ui.visualizer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.TileMode

/**
 * The one soft-circle bitmap every particle is drawn from.
 *
 * 260 particles at 60Hz is 260 radial-gradient shader builds per frame if each is a
 * `drawCircle(Brush.radialGradient(...))` — the gradient is rebuilt per call and the allocation
 * alone will stutter. One pre-rendered sprite `drawImage`d N times is a blit, and it is the
 * difference between this being affordable on a handheld and not.
 */

/** Source resolution. Large enough that the biggest on-screen particle is not upscaled to mush. */
private const val SPRITE_PX = 64

/**
 * Builds the sprite: opaque at the core, falling to fully transparent at the rim on a curve that
 * puts most of the falloff in the outer half, so particles read as glows rather than as discs with
 * soft edges.
 */
fun softCircleSprite(color: Color, sizePx: Int = SPRITE_PX): ImageBitmap {
    val bitmap = ImageBitmap(sizePx, sizePx)
    val canvas = Canvas(bitmap)
    val centre = Offset(sizePx / 2f, sizePx / 2f)
    val paint = Paint().apply {
        shader = RadialGradientShader(
            center = centre,
            radius = sizePx / 2f,
            colors = listOf(
                color.copy(alpha = 1f),
                color.copy(alpha = 0.55f),
                color.copy(alpha = 0f),
            ),
            colorStops = listOf(0f, 0.45f, 1f),
            tileMode = TileMode.Clamp,
        )
    }
    canvas.drawCircle(centre, sizePx / 2f, paint)
    return bitmap
}

/** Cached per colour, so a theme change rebuilds it and nothing else does. */
@Composable
fun rememberVisualizerSprite(color: Color): ImageBitmap = remember(color) { softCircleSprite(color) }
