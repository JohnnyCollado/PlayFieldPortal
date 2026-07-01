package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.wave.WaveStyle
import kotlin.math.sin

/** Frozen phase used when the wave animation is disabled. */
private const val STATIC_PHASE = 0.6f

/**
 * Root background behind the XMB UI. The wallpaper automatically replaces the wave:
 *
 *  - When [customWallpaperPath] is set, the wallpaper is rendered alone — no wave is drawn
 *    or animated, and no wave resources are allocated.
 *  - When no wallpaper is set, the XMB wave is rendered, honoring [waveStyle].
 */
@Composable
fun XmbBackground(
    waveStyle: WaveStyle,
    customWallpaperPath: String? = null,
    modifier: Modifier = Modifier,
) {
    if (customWallpaperPath != null) {
        WallpaperBackground(customWallpaperPath, modifier)
    } else {
        WaveBackground(waveStyle, modifier)
    }
}

@Composable
private fun WallpaperBackground(
    customWallpaperPath: String,
    modifier: Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        AsyncImage(
            model              = customWallpaperPath,
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize(),
        )
        // Light scrim so the XMB labels stay readable over any wallpaper.
        Box(Modifier.fillMaxSize().background(Color(0x59000000)))
    }
}

@Composable
private fun WaveBackground(
    waveStyle: WaveStyle,
    modifier: Modifier,
) {
    val colors = LocalPFPColors.current

    val phase = if (!waveStyle.animated) {
        STATIC_PHASE
    } else {
        // The driver sweeps one full 2π cycle and Restarts. For a seamless loop, every layer
        // must advance a whole number of 2π cycles over this span — drawXmbWaveLayers multiplies
        // this base phase by the integer cycle counts (5/6/7) to guarantee that. The duration is
        // the per-layer cycle time (≈6.2s) × the middle layer's 6 cycles so on-screen speed is
        // unchanged from the original single-cycle driver.
        val duration = if (waveStyle.reduced) 72000 else 37200
        val transition = rememberInfiniteTransition(label = "xmbBackgroundWave")
        val animatedPhase by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = duration, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "xmbBackgroundPhase",
        )
        animatedPhase
    }

    val alphaScale = if (waveStyle.reduced) 0.45f else 1f
    val ampScale   = if (waveStyle.reduced) 0.6f else 1f

    val gradient = Brush.linearGradient(
        colorStops = arrayOf(
            0.00f to colors.backgroundTop,
            // Hold the deep top colour through the upper portion (so the status strip keeps a solid
            // dark backdrop) before easing down to the pale bottom.
            0.30f to colors.backgroundTop,
            0.70f to lerp(colors.backgroundTop, colors.backgroundBottom, 0.5f),
            1.00f to colors.backgroundBottom,
        )
    )

    Box(modifier = modifier.fillMaxSize().background(gradient)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawXmbWaveLayers(phase, colors.waveColor, alphaScale, ampScale)
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                    center = center.copy(x = size.width * 0.48f, y = size.height * 0.30f),
                    radius = size.minDimension * 0.62f,
                )
            )
        }
    }
}

private fun DrawScope.drawXmbWaveLayers(phase: Float, waveColor: Color, alphaScale: Float, ampScale: Float) {
    val w = size.width
    val h = size.height
    // PSP "Original" waves: soft, near-white ribbons low on the screen — barely-there highlights
    // rather than coloured bands. The front layers fill in white (a gentle lightening of the pale
    // gradient) and one middle layer carries a faint trace of the theme hue for depth.
    val tint = lerp(waveColor, Color.White, 0.55f)
    // Each layer's phase = base × an INTEGER cycle count (+ a constant offset). The integer
    // multipliers (5/6/7) keep the original slow/medium/fast parallax ratio while ensuring every
    // layer completes whole cycles when the base wraps 2π → 0, so the loop has no visible skip.
    // Constant offsets don't affect seamlessness, only the layers' relative starting positions.
    drawWaveLayer(
        width = w, height = h, midY = h * 0.80f, phase = phase * 5f,
        amplitude = h * 0.055f * ampScale,
        color = Color.White.copy(alpha = 0.34f * alphaScale), points = 34,
    )
    drawWaveLayer(
        width = w, height = h, midY = h * 0.73f, phase = phase * 6f + 1.4f,
        amplitude = h * 0.050f * ampScale,
        color = tint.copy(alpha = 0.28f * alphaScale), points = 30,
    )
    drawWaveLayer(
        width = w, height = h, midY = h * 0.87f, phase = phase * 7f + 0.8f,
        amplitude = h * 0.042f * ampScale,
        color = Color.White.copy(alpha = 0.22f * alphaScale), points = 26,
    )
}

private fun DrawScope.drawWaveLayer(
    width: Float,
    height: Float,
    midY: Float,
    phase: Float,
    amplitude: Float,
    color: Color,
    points: Int,
) {
    val path = Path()
    val step = width / points
    path.moveTo(0f, height)
    path.lineTo(0f, midY + sin(phase).toFloat() * amplitude)
    for (i in 1..points) {
        val x = i * step
        val angle = (i.toFloat() / points) * 2f * Math.PI.toFloat() + phase
        val y = midY + sin(angle) * amplitude
        path.lineTo(x, y)
    }
    path.lineTo(width, height)
    path.close()
    drawPath(path, color)
}
