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
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.playfieldportal.core.ui.wave.WaveRenderMode
import kotlin.math.sin

private val XmbPurple = Color(0xFF3B148C)
private val XmbViolet = Color(0xFF26106C)
private val XmbBlue = Color(0xFF073B9C)
private val XmbDeepBlue = Color(0xFF061E69)
private val XmbCyanWave = Color(0xFF0FB5F4)
private val XmbIndigoWave = Color(0xFF5368FF)

@Composable
fun XmbBackground(
    renderMode: WaveRenderMode,
    customWallpaperPath: String? = null,
    modifier: Modifier = Modifier,
) {
    val phase = if (renderMode == WaveRenderMode.STATIC) {
        0.6f
    } else {
        val duration = if (renderMode == WaveRenderMode.FULL) 6200 else 12000
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

    if (customWallpaperPath != null) {
        // Custom wallpaper: fill with the image, then draw a dark scrim + waves on top.
        Box(modifier = modifier.fillMaxSize()) {
            AsyncImage(
                model              = customWallpaperPath,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
            // Dark scrim so the XMB UI stays readable over any wallpaper.
            Box(
                Modifier.fillMaxSize().background(Color(0x99000000))
            )
            // Waves drawn at reduced opacity to preserve the PSP feel.
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawXmbWaveLayersDimmed(phase)
            }
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colorStops = arrayOf(
                            0.00f to XmbViolet,
                            0.30f to XmbDeepBlue,
                            0.58f to XmbBlue,
                            1.00f to XmbPurple,
                        )
                    )
                )
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawXmbWaveLayers(phase)
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
}

private fun DrawScope.drawXmbWaveLayersDimmed(phase: Float) {
    val w = size.width
    val h = size.height
    drawWaveLayer(width = w, height = h, midY = h * 0.78f, phase = phase * 0.80f, amplitude = h * 0.075f,
        color = XmbCyanWave.copy(alpha = 0.30f), points = 34)
    drawWaveLayer(width = w, height = h, midY = h * 0.72f, phase = phase + 1.4f, amplitude = h * 0.065f,
        color = XmbIndigoWave.copy(alpha = 0.18f), points = 30)
    drawWaveLayer(width = w, height = h, midY = h * 0.84f, phase = phase * 1.18f + 0.8f, amplitude = h * 0.050f,
        color = Color(0xFF43D9FF).copy(alpha = 0.12f), points = 26)
}

private fun DrawScope.drawXmbWaveLayers(phase: Float) {
    val w = size.width
    val h = size.height
    drawWaveLayer(
        width = w,
        height = h,
        midY = h * 0.78f,
        phase = phase * 0.80f,
        amplitude = h * 0.075f,
        color = XmbCyanWave.copy(alpha = 0.74f),
        points = 34,
    )
    drawWaveLayer(
        width = w,
        height = h,
        midY = h * 0.72f,
        phase = phase + 1.4f,
        amplitude = h * 0.065f,
        color = XmbIndigoWave.copy(alpha = 0.42f),
        points = 30,
    )
    drawWaveLayer(
        width = w,
        height = h,
        midY = h * 0.84f,
        phase = phase * 1.18f + 0.8f,
        amplitude = h * 0.050f,
        color = Color(0xFF43D9FF).copy(alpha = 0.30f),
        points = 26,
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
