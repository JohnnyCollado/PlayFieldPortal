package com.playfieldportal.core.ui.wave

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.playfieldportal.core.ui.theme.LocalPFPColors
import kotlin.math.sin

/**
 * Visual treatment for the XMB wave. Only applies when no custom wallpaper is set — a
 * wallpaper automatically replaces the wave.
 *
 *  - [ANIMATED]        full opacity, animated
 *  - [REDUCED]         lower opacity + amplitude, animated (slower)
 *  - [STATIC]          full opacity, frozen wave — no animation updates
 *  - [REDUCED_STATIC]  lower opacity + amplitude, frozen wave
 */
enum class WaveStyle {
    ANIMATED,
    REDUCED,
    STATIC,
    REDUCED_STATIC;

    /** Whether the wave should animate at all. */
    val animated: Boolean get() = this == ANIMATED || this == REDUCED

    /** Whether the wave should be drawn at reduced opacity / amplitude. */
    val reduced: Boolean get() = this == REDUCED || this == REDUCED_STATIC
}

/** Fixed phase used to freeze the wave shape when animation is disabled. */
internal const val STATIC_WAVE_PHASE = 0.5f

@Composable
fun XMBWave(
    modifier: Modifier = Modifier,
    waveStyle: WaveStyle = WaveStyle.ANIMATED,
) {
    val colors = LocalPFPColors.current
    val alphaScale = if (waveStyle.reduced) 0.5f else 1f
    val ampScale   = if (waveStyle.reduced) 0.6f else 1f

    if (!waveStyle.animated) {
        // Frozen wave — no infinite transition, so no recompositions / animation updates.
        Canvas(modifier = modifier.fillMaxSize()) {
            drawWaveLayers(STATIC_WAVE_PHASE, colors.waveColor, alphaScale, ampScale)
        }
        return
    }

    val targetDurationMs = if (waveStyle.reduced) 12000 else 6000

    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = targetDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavePhase",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        drawWaveLayers(phase, colors.waveColor, alphaScale, ampScale)
    }
}

private fun DrawScope.drawWaveLayers(
    phase: Float,
    baseColor: Color,
    alphaScale: Float,
    ampScale: Float,
) {
    val w = size.width
    val h = size.height
    val midY = h * 0.72f        // wave sits in lower third — PSP-accurate position

    // Three layers for depth — same approach as PSP XMB
    drawWaveLayer(w, h, midY, phase,        amplitude = h * 0.06f * ampScale, color = baseColor.copy(alpha = 0.9f * alphaScale), points = 32)
    drawWaveLayer(w, h, midY, phase * 0.7f, amplitude = h * 0.04f * ampScale, color = baseColor.copy(alpha = 0.5f * alphaScale), points = 24)
    drawWaveLayer(w, h, midY, phase * 1.3f, amplitude = h * 0.03f * ampScale, color = baseColor.copy(alpha = 0.3f * alphaScale), points = 20)
}

private fun DrawScope.drawWaveLayer(
    w: Float, h: Float, midY: Float,
    phase: Float, amplitude: Float,
    color: Color, points: Int,
) {
    val path = Path()
    val step = w / points

    path.moveTo(0f, h)
    path.lineTo(0f, midY + sin(phase).toFloat() * amplitude)

    for (i in 1..points) {
        val x = i * step
        val angle = (i.toFloat() / points) * 2 * Math.PI.toFloat() + phase
        val y = midY + sin(angle) * amplitude
        path.lineTo(x, y)
    }

    path.lineTo(w, h)
    path.close()

    drawPath(path = path, color = color)
}
