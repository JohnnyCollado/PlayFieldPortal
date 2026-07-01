package com.playfieldportal.feature.xmb.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.wave.WaveStyle
import kotlin.math.sin

// Frozen "time" (seconds) used to pose the wave when animation is disabled.
private const val STATIC_TIME = 2.0f
private const val TAU = 6.2831853f

/**
 * Root background behind the XMB UI. The wallpaper automatically replaces the wave:
 *
 *  - When [customWallpaperPath] is set, the wallpaper is rendered alone — no wave is drawn
 *    or animated, and no wave resources are allocated.
 *  - When no wallpaper is set, the XMB wave is rendered, honoring [waveStyle].
 *
 * The wave itself is the authentic PSP XMB "heavenly" flow: a soft, luminous ribbon low on the
 * screen built from several summed sine waves, with a glowing crest and a scatter of sparkles over
 * a monthly-tinted vertical gradient. On API 33+ it's a single AGSL fragment shader; older devices
 * get a Canvas fallback (glowing stroked ribbons) that keeps the same silhouette.
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
    val alphaScale = if (waveStyle.reduced) 0.5f else 1f
    val ampScale   = if (waveStyle.reduced) 0.65f else 1f

    // Continuously-increasing time in seconds since the first frame (so float precision stays sharp),
    // scaled by style speed. Frozen at STATIC_TIME when the wave shouldn't animate — no frame loop,
    // no per-frame recomposition. Only advances while this background is on screen.
    val animated = waveStyle.animated
    val speed = if (waveStyle.reduced) 0.5f else 1f
    val time by produceState(STATIC_TIME, animated, speed) {
        if (!animated) {
            value = STATIC_TIME
            return@produceState
        }
        var startMs = -1L
        while (true) {
            withInfiniteAnimationFrameMillis { frameMs ->
                if (startMs < 0L) startMs = frameMs
                value = (frameMs - startMs) / 1000f * speed
            }
        }
    }

    // Monthly-tinted vertical gradient: deep top (keeps the status strip legible) easing to the pale
    // bottom the wave sits against.
    val gradient = Brush.linearGradient(
        colorStops = arrayOf(
            0.00f to colors.backgroundTop,
            0.30f to colors.backgroundTop,
            0.70f to lerp(colors.backgroundTop, colors.backgroundBottom, 0.5f),
            1.00f to colors.backgroundBottom,
        )
    )

    Box(modifier = modifier.fillMaxSize().background(gradient)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ShaderWave(time, alphaScale, ampScale)
        } else {
            FallbackWave(time, alphaScale, ampScale)
        }
        // Soft off-centre light bloom — the same gentle highlight the XMB has near the crossbar.
        Canvas(modifier = Modifier.fillMaxSize()) {
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

// ── AGSL wave (API 33+) ──────────────────────────────────────────────────────
// The real PSP "Original" wave: soft, long-wavelength light FOLDS — gentle luminance sheets that
// blend the gradient beneath toward white. Low contrast, no hard ribbon, no sparkles. Because it
// only ever lightens the gradient, the colour always comes from whatever theme is applied.
private const val AGSL_WAVE = """
uniform float2 iResolution;
uniform float  iTime;
uniform float  ampScale;
uniform float  alphaScale;

const float TAU = 6.2831853;

// One fold: a broad soft sheet of light below crest [c], plus a faint luminous crest line.
float fold(float y, float c, float sheet, float edge) {
    float body = smoothstep(0.0, 0.17, y - c) * sheet;   // brightens the region below the crest
    float line = exp(-pow((y - c) * 42.0, 2.0)) * edge;  // thin highlight riding the crest
    return body + line;
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;   // 0..1, y down
    float x = uv.x;
    float t = iTime;
    float a = 0.05 * ampScale;

    // Two overlapping fold crests low on the screen (top ribbon removed).
    float c2 = 0.63 + a * 0.9 * sin(x * TAU * 0.80 - t * 0.38 + 1.7);
    float c3 = 0.75 + a * 1.2 * sin(x * TAU * 0.42 + t * 0.30 + 3.1);

    float b = fold(uv.y, c2, 0.090, 0.125)
            + fold(uv.y, c3, 0.105, 0.145);

    b = clamp(b * alphaScale, 0.0, 0.6);
    return half4(1.0, 1.0, 1.0, 1.0) * b;   // premultiplied white → SrcOver blends the gradient toward white
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderWave(time: Float, alphaScale: Float, ampScale: Float) {
    val shader = remember { RuntimeShader(AGSL_WAVE) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Canvas(modifier = Modifier.fillMaxSize()) {
        shader.setFloatUniform("iResolution", size.width, size.height)
        shader.setFloatUniform("iTime", time)   // reading `time` here drives the per-frame redraw
        shader.setFloatUniform("ampScale", ampScale)
        shader.setFloatUniform("alphaScale", alphaScale)
        drawRect(brush = brush)
    }
}

// ── Canvas fallback (API < 33) ───────────────────────────────────────────────
// Same soft folds approximated with low-alpha white fills (the sheet) + faint crest strokes.
@Composable
private fun FallbackWave(time: Float, alphaScale: Float, ampScale: Float) {
    val amp = 0.05f * ampScale
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawFold(time, base01 = 0.63f, amp01 = amp * 0.9f, freq = 0.80f, phase = 1.7f, drift = -0.38f, sheet = 0.090f * alphaScale, edge = 0.125f * alphaScale)
        drawFold(time, base01 = 0.75f, amp01 = amp * 1.2f, freq = 0.42f, phase = 3.1f, drift = 0.30f,  sheet = 0.105f * alphaScale, edge = 0.145f * alphaScale)
    }
}

private fun DrawScope.drawFold(
    t: Float, base01: Float, amp01: Float, freq: Float, phase: Float, drift: Float,
    sheet: Float, edge: Float,
) {
    val w = size.width
    val h = size.height
    val n = 48
    val crestPath = Path()
    val fillPath = Path()
    fillPath.moveTo(0f, h)
    for (i in 0..n) {
        val xx = i / n.toFloat()
        val y = (base01 + amp01 * sin(xx * TAU * freq + t * drift + phase)) * h
        val x = xx * w
        if (i == 0) { crestPath.moveTo(x, y); fillPath.lineTo(x, y) }
        else { crestPath.lineTo(x, y); fillPath.lineTo(x, y) }
    }
    fillPath.lineTo(w, h)
    fillPath.close()

    // Sheet: a flat, faint white wash from the crest down — stacking the folds brightens the lower
    // screen like the reference. Crest: two soft white strokes for the gentle fold highlight.
    drawPath(fillPath, color = Color.White.copy(alpha = sheet))
    drawPath(crestPath, color = Color.White.copy(alpha = edge * 0.5f), style = Stroke(width = h * 0.022f))
    drawPath(crestPath, color = Color.White.copy(alpha = edge), style = Stroke(width = h * 0.006f))
}
