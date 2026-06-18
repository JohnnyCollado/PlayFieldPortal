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

enum class WaveRenderMode { FULL, REDUCED, STATIC }

@Composable
fun XMBWave(
    modifier: Modifier = Modifier,
    renderMode: WaveRenderMode = WaveRenderMode.FULL,
) {
    val colors = LocalPFPColors.current

    if (renderMode == WaveRenderMode.STATIC) {
        // Zero-cost static fallback — frozen wave shape
        Canvas(modifier = modifier.fillMaxSize()) {
            drawStaticWave(colors.waveColor, colors.waveColor.copy(alpha = 0.4f))
        }
        return
    }

    val targetDurationMs = if (renderMode == WaveRenderMode.FULL) 6000 else 12000

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
        drawWaveLayers(phase, colors.waveColor)
    }
}

private fun DrawScope.drawWaveLayers(phase: Float, baseColor: Color) {
    val w = size.width
    val h = size.height
    val midY = h * 0.72f        // wave sits in lower third — PSP-accurate position

    // Three layers for depth — same approach as PSP XMB
    drawWaveLayer(w, h, midY, phase,        amplitude = h * 0.06f, color = baseColor.copy(alpha = 0.9f), points = 32)
    drawWaveLayer(w, h, midY, phase * 0.7f, amplitude = h * 0.04f, color = baseColor.copy(alpha = 0.5f), points = 24)
    drawWaveLayer(w, h, midY, phase * 1.3f, amplitude = h * 0.03f, color = baseColor.copy(alpha = 0.3f), points = 20)
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

private fun DrawScope.drawStaticWave(color: Color, secondaryColor: Color) {
    drawWaveLayer(size.width, size.height, size.height * 0.72f,
        phase = 0.5f, amplitude = size.height * 0.06f,
        color = color.copy(alpha = 0.9f), points = 32)
    drawWaveLayer(size.width, size.height, size.height * 0.72f,
        phase = 1.2f, amplitude = size.height * 0.04f,
        color = secondaryColor, points = 24)
}
