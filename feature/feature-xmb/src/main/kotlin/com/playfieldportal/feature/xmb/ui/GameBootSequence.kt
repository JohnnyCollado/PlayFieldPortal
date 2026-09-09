package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.wave.WaveStyle
import kotlin.math.exp
import kotlin.math.pow

/**
 * The full length of the bundled `sfx_launch.wav` — 220 500 frames at 44.1 kHz, exactly 5.000 s.
 * The sequence and the sound start together and end together; nothing here is padded or clipped.
 */
private const val SEQUENCE_MS = 5_000

/** The sound's leading digital silence — the sequence holds pure black across it. */
private const val LEAD_SILENCE_MS = 350f

/**
 * The measured loudness of `sfx_launch.wav`: 50 ms-window RMS, normalized to its own peak
 * (0.388 at 3 000 ms), decimated to the points where the curve actually changes direction.
 *
 * This table is why the light lands on the sound instead of near it. The bloom's brightness IS
 * this curve, so the first hit (2 050 ms), the main body (2 900–3 300 ms) and the late lift at
 * 4 250 ms show up on screen without anyone hand-tuning a fade to match them by ear. Re-measure
 * and replace the table if the sample is ever swapped — see assets/SFX/REFERENCE.md.
 */
private val LOUDNESS: List<Pair<Int, Float>> = listOf(
    0 to 0.00f, 350 to 0.00f, 900 to 0.06f, 1_400 to 0.26f, 1_700 to 0.47f,
    2_050 to 0.86f, 2_250 to 0.45f, 2_500 to 0.51f, 2_900 to 0.87f, 3_100 to 1.00f,
    3_300 to 0.89f, 3_700 to 0.57f, 3_950 to 0.39f, 4_250 to 0.80f, 4_400 to 0.49f,
    4_800 to 0.43f, 5_000 to 0.00f,
)

// The two sweeps, each windowed on one of the sound's attacks (crest at the attack's own ms).
private const val FIRST_SWEEP_START_MS = 1_550
private const val FIRST_SWEEP_END_MS = 2_250
private const val SECOND_SWEEP_START_MS = 2_450
private const val SECOND_SWEEP_END_MS = 3_350

// The title rises out of the second sweep's peak and rides the sequence out.
private const val TITLE_FADE_START_MS = 2_900
private const val TITLE_FADE_MS = 500

// The whole frame sinks to black over the sound's own fade-out (4 850-5 000 ms), started early
// enough that the screen is genuinely black when the emulator takes it.
private const val DECAY_START_MS = 4_400
private const val DECAY_MS = 600

/**
 * The built-in GameBoot presentation — a PSP-style light sweep drawn in Compose, beat-matched to
 * the bundled launch sound over its full 5.000 s.
 *
 * This is GameBoot's default, and deliberately NOT a bundled video: it is drawn on a surface that
 * is already live, so it costs no decoder warm-up at the one moment the user is waiting for their
 * game, it scales to any screen, it picks up the user's theme colours, and it can drop its motion
 * without dropping its timing — none of which a shipped MP4 could do. A user who wants something
 * else assigns their own clip, and [GameBootOverlay] plays that instead, with its own audio.
 *
 * **Real time, not animation time.** The timeline is driven by [withFrameMillis] deltas rather
 * than an `Animatable` + `tween`, because tween durations are scaled by the system's animator
 * duration setting: with animations turned down (developer options, or a battery/accessibility
 * profile) a tween-driven sequence finishes early or instantly, the gate's await returns, and the
 * emulator takes the screen while the sound is still playing. Frame deltas are wall-clock, so
 * five seconds is five seconds and the light stays locked to the sample it was measured from.
 *
 * **Motion budget: less motion, not less time.** [BootSequenceOverlay] hardcodes
 * `WaveStyle.ANIMATED` because it runs once per app start. GameBoot runs on EVERY launch, so it
 * honors the user's wave style instead — a deliberate deviation from BootSequenceOverlay's stance.
 * A frozen or reduced style replaces the bloom and both sweeps with one still frame, but the
 * presentation still runs its full length. Shortening it instead would end the visual while the
 * sound was still going and hand the screen to the emulator mid-presentation, which is the one
 * thing this sequence must not do.
 */
@Composable
fun GameBootSequence(
    gameTitle: String,
    waveStyle: WaveStyle,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPFPColors.current
    val reduced = !waveStyle.animated || waveStyle.reduced
    var ms by remember { mutableFloatStateOf(0f) }
    val currentOnFinished by rememberUpdatedState(onFinished)

    LaunchedEffect(Unit) {
        // Wall-clock frame deltas: see the KDoc — an animator-scaled tween would let the launch
        // overtake the presentation. One frame per iteration, so `ms` advances once per drawn
        // frame and nothing polls.
        val startMs = withFrameMillis { it }
        while (true) {
            val elapsed = (withFrameMillis { it } - startMs).toFloat()
            ms = elapsed.coerceAtMost(SEQUENCE_MS.toFloat())
            if (elapsed >= SEQUENCE_MS) break
        }
        currentOnFinished()
    }

    val decay = 1f - ((ms - DECAY_START_MS) / DECAY_MS).coerceIn(0f, 1f)
    // The reduced form shows the title for the whole presentation rather than fading it in, but
    // still sinks to black at the end: the emulator must never be handed a lit screen.
    val titleAlpha = if (reduced) decay else {
        val fadeIn = ((ms - TITLE_FADE_START_MS) / TITLE_FADE_MS).coerceIn(0f, 1f)
        fadeIn * decay
    }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Theme gradient base — the wave's own convention: white light over the user's theme
            // rather than a tinted light, so the sequence picks up custom themes for free.
            drawRect(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0.00f to colors.backgroundTop,
                        0.70f to lerp(colors.backgroundTop, colors.backgroundBottom, 0.5f),
                        1.00f to colors.backgroundBottom,
                    )
                )
            )

            if (reduced) {
                // One still frame for the whole presentation — no bloom growth, no sweeps. It
                // runs the same five seconds so the visual and the sound end together.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.30f * decay), Color.Transparent),
                        center = center.copy(x = size.width * 0.5f, y = size.height * 0.56f),
                        radius = size.minDimension * 0.55f,
                    )
                )
                if (decay < 1f) drawRect(Color.Black.copy(alpha = 1f - decay))
                return@Canvas
            }

            // The bloom rides the measured loudness: it grows and brightens exactly where the
            // sound does, including the dip after the first hit and the late lift at 4 250 ms.
            val loudness = loudnessAt(ms)
            val bloomAlpha = loudness * decay
            if (bloomAlpha > 0.001f) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.30f * bloomAlpha), Color.Transparent),
                        center = center.copy(x = size.width * 0.5f, y = size.height * 0.56f),
                        radius = size.minDimension * (0.30f + 0.50f * loudness),
                    )
                )
            }

            // Two sweeps, one per attack: a narrow scout on the 2 050 ms hit, then a wider,
            // brighter pass whose crest lands on the 2 900 ms peak.
            sweepProgress(ms, FIRST_SWEEP_START_MS, FIRST_SWEEP_END_MS)?.let {
                drawSweep(size.width, it, intensity = 0.55f * decay)
            }
            sweepProgress(ms, SECOND_SWEEP_START_MS, SECOND_SWEEP_END_MS)?.let {
                drawSweep(size.width, it, intensity = decay)
            }

            // Pure-black hold over the sound's leading silence, and the matching sink to black
            // under its fade-out: the sequence starts and ends on black by construction.
            val hold = 1f - (ms / LEAD_SILENCE_MS).coerceIn(0f, 1f)
            if (hold > 0f) drawRect(Color.Black.copy(alpha = hold))
            if (decay < 1f) drawRect(Color.Black.copy(alpha = 1f - decay))
        }

        Text(
            text = gameTitle,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 24.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 2.sp,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 48.dp)
                .alpha(titleAlpha),
        )
    }
}

/**
 * The measured loudness at [ms], linearly interpolated between [LOUDNESS] points. Clamped at both
 * ends so a frame delivered slightly past [SEQUENCE_MS] reads as silence rather than wrapping.
 */
private fun loudnessAt(ms: Float): Float {
    if (ms <= LOUDNESS.first().first) return LOUDNESS.first().second
    if (ms >= LOUDNESS.last().first) return LOUDNESS.last().second
    for (i in 1 until LOUDNESS.size) {
        val (endMs, endValue) = LOUDNESS[i]
        if (ms > endMs) continue
        val (startMs, startValue) = LOUDNESS[i - 1]
        val t = (ms - startMs) / (endMs - startMs).toFloat()
        return startValue + (endValue - startValue) * t
    }
    return LOUDNESS.last().second
}

/** 0 to 1 across a sweep's window, or null when [ms] is outside it and nothing should be drawn. */
private fun sweepProgress(ms: Float, startMs: Int, endMs: Int): Float? =
    if (ms < startMs || ms > endMs) null else (ms - startMs) / (endMs - startMs)

/**
 * One vertical light band crossing the screen left to right. Per column: an exp crest (the bright
 * band) over a smoothstep sheet (the wash trailing it) — the same construction `drawFold` uses
 * for the XMB wave's horizontal folds, rotated 90 degrees so the light moves across instead of
 * sitting at the bottom. [t] is 0 to 1 across the sweep's window; the crest travels 8% to 92% of
 * the width. [intensity] scales the whole band, which is what makes the second pass the bigger one.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSweep(
    w: Float,
    t: Float,
    intensity: Float,
) {
    if (intensity <= 0.001f) return
    val centre = w * (0.08f + 0.84f * t)
    val bandWidth = w * 0.28f
    val n = 24
    val stops = List(n) { i ->
        val x = i / (n - 1).toFloat() * w
        val u = (x - centre) / bandWidth
        val crest = exp(-(u * 2.6f).pow(2))              // the bright band itself
        val sheet = smoothstep(-1.4f, 0.55f, u) * 0.45f  // the wash trailing the crest
        val alpha = ((crest * 0.85f + sheet) * intensity).coerceIn(0f, 0.8f)
        Color.White.copy(alpha = alpha)
    }
    drawRect(brush = Brush.horizontalGradient(stops))
}

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
