package com.playfieldportal.feature.xmb.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.RuntimeShader
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import com.playfieldportal.core.ui.motion.MotionWallpaperBackground
import com.playfieldportal.core.ui.motion.MotionWallpaperPolicy
import com.playfieldportal.themekit.MotionCrop
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.wave.WaveStyle
import kotlin.math.sin
import kotlinx.coroutines.delay

// Frozen "time" (seconds) used to pose the wave when animation is disabled.
private const val STATIC_TIME = 2.0f
private const val TAU = 6.2831853f

/**
 * Root background behind the XMB UI. The wallpaper automatically replaces the wave:
 *
 *  - When [motionWallpaperPath] is set, the looping motion wallpaper renders over its poster —
 *    but ONLY while [motionDecision] says PLAY/PLAY_REDUCED; a POSTER decision renders the poster
 *    still alone and never constructs an ExoPlayer. (When the wave would be frozen, the motion
 *    wallpaper is not merely paused — no decoder exists.)
 *  - Otherwise, when [customWallpaperPath] is set, the still wallpaper renders alone — no wave
 *    is drawn or animated, and no wave resources are allocated. A POSTER decision with a motion
 *    file set lands here too: the frozen path is literally the existing still-wallpaper composable.
 *  - When no wallpaper is set, the XMB wave is rendered, honoring [waveStyle].
 *
 * The motion decision itself is computed ONCE by the shell through [MotionWallpaperPolicy] (the
 * single definition of "the device is busy or conserving" that both the shader and the decoder
 * obey) and passed down; callers that render only a wave (boot) can ignore it entirely.
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
    motionWallpaperPath: String? = null,
    motionDecision: MotionWallpaperPolicy.Decision = MotionWallpaperPolicy.Decision.PLAY,
    modifier: Modifier = Modifier,
    motionCrop: MotionCrop? = null,
) {
    when {
        customWallpaperPath != null && motionWallpaperPath != null && motionDecision != MotionWallpaperPolicy.Decision.POSTER ->
            MotionWallpaperBackground(
                posterPath = customWallpaperPath,
                motionPath = motionWallpaperPath,
                decision = motionDecision,
                modifier = modifier,
                motionCrop = motionCrop,
            )
        customWallpaperPath != null -> WallpaperBackground(customWallpaperPath, modifier)
        else -> WaveBackground(waveStyle, modifier)
    }
}

/**
 * Whether the XMB wave animation should be frozen to relieve the device, honoring the two
 * Settings ▸ Display toggles. The wave is a non-essential flourish, so it shouldn't burn power when
 * the system is already conserving it:
 *
 *  - [respectBatterySaver]: true while the OS is in battery-saver mode (live via
 *    `ACTION_POWER_SAVE_MODE_CHANGED`).
 *  - [thermalThrottleAware]: true while the OS reports at least moderate thermal throttling (live via
 *    a thermal-status listener; API 29+ only).
 *
 * Returns false whenever the matching setting is off or the platform can't report the state.
 */
@Composable
fun rememberWavePowerThrottle(
    respectBatterySaver: Boolean,
    thermalThrottleAware: Boolean,
): Boolean {
    val context = LocalContext.current
    val powerManager = remember(context) { context.getSystemService(PowerManager::class.java) }

    // Battery-saver: seed from the current value, then track ACTION_POWER_SAVE_MODE_CHANGED.
    var powerSave by remember { mutableStateOf(false) }
    DisposableEffect(powerManager, respectBatterySaver) {
        if (powerManager == null || !respectBatterySaver) {
            powerSave = false
            return@DisposableEffect onDispose {}
        }
        powerSave = powerManager.isPowerSaveMode
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                powerSave = powerManager.isPowerSaveMode
            }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    // Thermal throttling: status listener (available since API 29 == minSdk); freeze at MODERATE+.
    var thermalThrottling by remember { mutableStateOf(false) }
    DisposableEffect(powerManager, thermalThrottleAware) {
        if (powerManager == null || !thermalThrottleAware) {
            thermalThrottling = false
            return@DisposableEffect onDispose {}
        }
        thermalThrottling = powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            thermalThrottling = status >= PowerManager.THERMAL_STATUS_MODERATE
        }
        powerManager.addThermalStatusListener(listener)
        onDispose { powerManager.removeThermalStatusListener(listener) }
    }

    return powerSave || thermalThrottling
}

@Composable
private fun WallpaperBackground(
    customWallpaperPath: String,
    modifier: Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        com.playfieldportal.core.ui.motion.ArtworkImage(
            model              = customWallpaperPath,
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize(),
        )
        // Light scrim so the XMB labels stay readable over any wallpaper.
        Box(Modifier.fillMaxSize().background(com.playfieldportal.core.ui.theme.ThemeTokens.WallpaperScrim))
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
    // scaled by style speed. Frozen at STATIC_TIME when the wave shouldn't animate — no frame loop.
    // Only advances while this background is on screen.
    //
    // Two things keep it cheap. It is READ ONLY WHILE DRAWING (the waves take a State, not a
    // Float), so a tick repaints the wave's own layer and never recomposes this tree. And it ticks
    // at ~30 fps (WAVE_FRAME_INTERVAL_MS), not every vsync: the folds drift over seconds, and the
    // panel runs at 60-144 Hz. The clock is wall time, so the cap never changes the speed.
    val animated = waveStyle.animated
    val speed = if (waveStyle.reduced) 0.5f else 1f
    val time = produceState(STATIC_TIME, animated, speed) {
        if (!animated) {
            value = STATIC_TIME
            return@produceState
        }
        var startMs = -1L
        while (true) {
            withInfiniteAnimationFrameMillis { frameMs ->
                if (startMs < 0L) startMs = frameMs
                value = waveTimeSeconds(frameMs, startMs, speed)
            }
            delay(WAVE_FRAME_INTERVAL_MS)
        }
    }

    // Monthly-tinted vertical gradient: deep top (keeps the status strip legible) easing to the pale
    // bottom the wave sits against. Rebuilt only when the theme colours change.
    val gradient = remember(colors.backgroundTop, colors.backgroundBottom) {
        Brush.linearGradient(
            colorStops = arrayOf(
                0.00f to colors.backgroundTop,
                0.30f to colors.backgroundTop,
                0.70f to lerp(colors.backgroundTop, colors.backgroundBottom, 0.5f),
                1.00f to colors.backgroundBottom,
            )
        )
    }

    Box(modifier = modifier.fillMaxSize().background(gradient)) {
        // Its own layer, so each tick re-records only the wave — not the gradient, the bloom, or
        // anything drawn alongside the background.
        val waveLayer = Modifier.fillMaxSize().graphicsLayer()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ShaderWave(time, alphaScale, ampScale, waveLayer)
        } else {
            FallbackWave(time, alphaScale, ampScale, waveLayer)
        }
        // Soft off-centre light bloom — the same gentle highlight the XMB has near the crossbar.
        // The brush is built once per size, never per frame.
        Spacer(
            modifier = Modifier.fillMaxSize().drawWithCache {
                val bloom = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(x = size.width * 0.48f, y = size.height * 0.30f),
                    radius = size.minDimension * 0.62f,
                )
                onDrawBehind { drawRect(brush = bloom) }
            },
        )
    }
}

/** The animated wave repaints at ~30 fps; see WaveBackground. */
internal const val WAVE_FRAME_INTERVAL_MS = 33L

/** The wave's clock: seconds since its first frame, scaled by the style's speed. */
internal fun waveTimeSeconds(frameMs: Long, startMs: Long, speed: Float): Float =
    (frameMs - startMs) / 1000f * speed

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
private fun ShaderWave(time: State<Float>, alphaScale: Float, ampScale: Float, modifier: Modifier) {
    val shader = remember { RuntimeShader(AGSL_WAVE) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Canvas(modifier = modifier) {
        shader.setFloatUniform("iResolution", size.width, size.height)
        // Read here, in the draw phase: a tick invalidates this draw only, never composition.
        shader.setFloatUniform("iTime", time.value)
        shader.setFloatUniform("ampScale", ampScale)
        shader.setFloatUniform("alphaScale", alphaScale)
        drawRect(brush = brush)
    }
}

// ── Canvas fallback (API < 33) ───────────────────────────────────────────────
// Same soft folds approximated with low-alpha white fills (the sheet) + faint crest strokes.
@Composable
private fun FallbackWave(time: State<Float>, alphaScale: Float, ampScale: Float, modifier: Modifier) {
    val amp = 0.05f * ampScale
    Spacer(
        modifier = modifier.drawWithCache {
            // Paths and strokes are made once per size and reused every frame; only their points move.
            val crest = Path()
            val fill = Path()
            val wide = Stroke(width = size.height * 0.022f)
            val thin = Stroke(width = size.height * 0.006f)
            onDrawBehind {
                // Read here, in the draw phase: a tick invalidates this draw only, never composition.
                val t = time.value
                drawFold(t, crest, fill, wide, thin, base01 = 0.63f, amp01 = amp * 0.9f, freq = 0.80f, phase = 1.7f, drift = -0.38f, sheet = 0.090f * alphaScale, edge = 0.125f * alphaScale)
                drawFold(t, crest, fill, wide, thin, base01 = 0.75f, amp01 = amp * 1.2f, freq = 0.42f, phase = 3.1f, drift = 0.30f,  sheet = 0.105f * alphaScale, edge = 0.145f * alphaScale)
            }
        },
    )
}

private fun DrawScope.drawFold(
    t: Float, crestPath: Path, fillPath: Path, wide: Stroke, thin: Stroke,
    base01: Float, amp01: Float, freq: Float, phase: Float, drift: Float,
    sheet: Float, edge: Float,
) {
    val w = size.width
    val h = size.height
    val n = 48
    crestPath.reset()
    fillPath.reset()
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
    drawPath(crestPath, color = Color.White.copy(alpha = edge * 0.5f), style = wide)
    drawPath(crestPath, color = Color.White.copy(alpha = edge), style = thin)
}
