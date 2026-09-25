package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.theme.InterFamily
import com.playfieldportal.core.ui.wave.WaveStyle
import com.playfieldportal.themekit.UiMediaLimits
import kotlin.math.PI
import kotlin.math.sin

/**
 * The sequence's full length, and the full length of the bundled `sfx_launch.wav` it is drawn
 * against — 220 500 frames at 44.1 kHz, exactly 5.000 s. Shared with the gate rather than
 * redeclared, so the sound and the picture cannot drift apart by one of them being edited alone.
 */
internal const val SEQUENCE_MS = UiMediaLimits.GAMEBOOT_SEQUENCE_MS

/**
 * The measured loudness of `sfx_launch.wav`: 50 ms-window RMS, normalized to its own peak
 * (0.388 at 3 000 ms), decimated to the points where the curve actually changes direction.
 *
 * This table is why the light lands on the sound instead of near it. Every bloom's brightness is
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

// The screen comes up out of black across the sound's own rise (0.06 at 900 ms to 0.47 at
// 1 700 ms), so the mark and the title resolve as the sound swells rather than on a cut.
private const val RAMP_START_MS = 900f
private const val RAMP_END_MS = 1_700f

/**
 * The three light events, each a window the bloom travels across. They are placed on the sound's
 * three attacks, not spaced for their own sake: a narrow scout on the 2 050 ms hit, the full pass
 * cresting on the 3 100 ms peak, and a faint answer on the late lift at 4 250 ms.
 *
 * A window only says WHERE the light is and how far it has travelled. How bright it is at any
 * instant comes from [LOUDNESS], tapered to nothing at each window's edges — so re-measuring the
 * table after a sample swap re-lights the whole sequence without anyone touching these numbers.
 */
internal val SWEEPS: List<Sweep> = listOf(
    Sweep(startMs = 1_700, endMs = 2_400, intensity = 0.50f),
    Sweep(startMs = 2_500, endMs = 3_500, intensity = 1.00f),
    Sweep(startMs = 4_100, endMs = 4_500, intensity = 0.26f),
)

// The whole frame sinks to black over the sound's own fade-out (4 850-5 000 ms), started early
// enough that the screen is genuinely black when the emulator takes it. The screen the sequence
// imitates simply ends on white; here something else takes the surface next, and handing an
// emulator a lit white frame is a visible flash.
private const val DECAY_START_MS = 4_400
private const val DECAY_MS = 600

// The field and its ink. Fixed rather than themed: this is a recreation of one specific screen,
// and a themed field would make it a different screen that happens to move the same way. The
// user's route to a look of their own is assigning their own clip, which replaces the whole
// presentation — see GameBootOverlay.
private val FIELD = Color.White
private val MARK_INK = Color(0xFF9A9AA4)
private val TITLE_INK = Color(0xFFA6A6AE)

// The bloom's three tints, laid down with BlendMode.Multiply so they darken the white field into
// colour the way a lens flare across a white screen does, rather than adding light to something
// that is already at full brightness and cannot get brighter.
private val BLOOM_WARM = Color(0xFFF6F05A)
private val BLOOM_COOL = Color(0xFF6EE8E0)
private val BLOOM_MID = Color(0xFFA8EC78)

// Layout, in fractions of the drawn area so the composition holds at any aspect ratio.
private const val MARK_CENTER_Y = 0.40f
private const val MARK_HEIGHT_FRACTION = 0.18f
private const val TITLE_TOP_Y = 0.585f
private const val TITLE_WIDTH_FRACTION = 0.80f

/** One light event: the window it travels across, and how strong it is at full loudness. */
internal data class Sweep(val startMs: Int, val endMs: Int, val intensity: Float)

/**
 * The built-in GameBoot presentation — a white field, a monoline `PFP` mark with the game's title
 * under it, and a chromatic bloom that crosses the screen on the bundled launch sound's attacks.
 *
 * This is GameBoot's default, and deliberately NOT a bundled video: it is drawn on a surface that
 * is already live, so it costs no decoder warm-up at the one moment the user is waiting for their
 * game, it scales to any screen, and it can drop its motion without dropping its timing — none of
 * which a shipped MP4 could do. A user who wants something else assigns their own clip, and
 * [GameBootOverlay] plays that instead, with its own audio.
 *
 * **The mark is drawn, not set.** `PFP` is a [Path] of uniform right-angle strokes rather than
 * text, which is what lets it stay crisp at any size and survive a reduced-motion pass at full
 * quality. Only the title beneath it is type, in [InterFamily].
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
 * A frozen or reduced style drops all three blooms and leaves the two crossfades, so the screen
 * still comes up out of black and still sinks back into it, over the same five seconds.
 * Shortening it instead would end the visual while the sound was still going and hand the screen
 * to the emulator mid-presentation, which is the one thing this sequence must not do.
 */
@Composable
fun GameBootSequence(
    gameTitle: String,
    waveStyle: WaveStyle,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = !waveStyle.animated || waveStyle.reduced
    var ms by remember { mutableFloatStateOf(0f) }
    val currentOnFinished by rememberUpdatedState(onFinished)
    // Measuring inside the draw phase would normally be wasteful, but the style and constraints
    // are identical on every frame, so all five thousand milliseconds of it are one cache hit.
    val textMeasurer = rememberTextMeasurer()

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

    Canvas(modifier = modifier) {
        // The opaque field goes down first and covers the whole surface. That is what makes
        // BlendMode.Multiply below correct without an offscreen layer: the destination the blooms
        // read is this white, never the black the overlay painted underneath.
        drawRect(FIELD)
        drawPfpMark()
        drawGameTitle(textMeasurer, gameTitle)

        if (!reduced) SWEEPS.forEach { drawSweep(it, ms) }

        // The curtain the screen comes up through, and the one it goes back down behind. Drawn
        // last, over everything: the mark and title resolve out of the black rather than being
        // switched on behind it.
        val curtain = 1f - rampAt(ms)
        if (curtain > 0f) drawRect(Color.Black.copy(alpha = curtain))
        val sink = ((ms - DECAY_START_MS) / DECAY_MS).coerceIn(0f, 1f)
        if (sink > 0f) drawRect(Color.Black.copy(alpha = sink))
    }
}

/** The black-to-white rise, 0 while the screen is still black and 1 once the field is fully up. */
internal fun rampAt(ms: Float): Float =
    ((ms - RAMP_START_MS) / (RAMP_END_MS - RAMP_START_MS)).coerceIn(0f, 1f)

/**
 * The measured loudness at [ms], linearly interpolated between [LOUDNESS] points. Clamped at both
 * ends so a frame delivered slightly past [SEQUENCE_MS] reads as silence rather than wrapping.
 */
internal fun loudnessAt(ms: Float): Float {
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
private fun Sweep.progressAt(ms: Float): Float? =
    if (ms < startMs || ms > endMs) null else (ms - startMs) / (endMs - startMs).toFloat()

/**
 * How bright a sweep is at [ms]: the sound's own loudness there, scaled by the sweep's
 * [Sweep.intensity] and tapered by a half-sine across its window. The taper is the only reason
 * the envelope is not the raw table — without it a window would switch on and off at whatever
 * level the sound happened to be at, and the second pass in particular would vanish mid-body.
 */
internal fun Sweep.amplitudeAt(ms: Float): Float {
    val p = progressAt(ms) ?: return 0f
    return loudnessAt(ms) * sin(PI.toFloat() * p) * intensity
}

/** Smoothstep — eases the bloom's travel so it drifts in and settles rather than tracking linearly. */
private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

/**
 * One pass of the bloom: three multiplied tints that turn the white field cyan through yellow as
 * they cross it, plus two additive flares riding above the warm core. The cluster travels right to
 * left — the reference's light enters from off the right edge and sweeps past the mark.
 */
private fun DrawScope.drawSweep(sweep: Sweep, ms: Float) {
    val amp = sweep.amplitudeAt(ms)
    if (amp <= 0.002f) return
    val travelled = smoothstep(sweep.progressAt(ms) ?: return)
    val cx = size.width * (0.92f - 0.50f * travelled)
    val cy = size.height * MARK_CENTER_Y

    bloom(BLOOM_WARM, cx, cy, size.width * 0.55f, 0.55f * amp, BlendMode.Multiply)
    bloom(BLOOM_COOL, cx - size.width * 0.30f, cy + size.height * 0.05f, size.width * 0.48f, 0.50f * amp, BlendMode.Multiply)
    bloom(BLOOM_MID, cx + size.width * 0.22f, cy - size.height * 0.12f, size.width * 0.26f, 0.30f * amp, BlendMode.Multiply)
    bloom(Color.White, cx + size.width * 0.10f, cy - size.height * 0.18f, size.width * 0.10f, 0.85f * amp, BlendMode.Plus)
    bloom(Color.White, cx + size.width * 0.19f, cy - size.height * 0.08f, size.width * 0.045f, 0.70f * amp, BlendMode.Plus)
}

/**
 * One radial falloff of [color] over the whole surface. The outer stop is the same colour at zero
 * alpha rather than [Color.Transparent] — a transparent BLACK stop would drag the multiplied tints
 * toward grey as they faded out.
 */
private fun DrawScope.bloom(
    color: Color,
    cx: Float,
    cy: Float,
    radius: Float,
    alpha: Float,
    blendMode: BlendMode,
) {
    if (alpha <= 0.002f) return
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.00f to color.copy(alpha = alpha),
                0.55f to color.copy(alpha = alpha * 0.55f),
                1.00f to color.copy(alpha = 0f),
            ),
            center = Offset(cx, cy),
            radius = radius,
        ),
        blendMode = blendMode,
    )
}

/**
 * The `PFP` mark: three monoline letters on one stroke weight, all right angles, mitred joins and
 * butt caps. Every measurement is a fraction of the mark's own height, so the whole lockup scales
 * as one thing.
 */
private fun DrawScope.drawPfpMark() {
    val h = size.height * MARK_HEIGHT_FRACTION
    val s = h * 0.085f
    val w = h * 0.62f
    val gap = h * 0.30f
    val left = (size.width - (3 * w + 2 * gap)) / 2f
    val cy = size.height * MARK_CENTER_Y
    val top = cy - h / 2f
    val bottom = cy + h / 2f
    val bar = cy - h * 0.04f
    val style = Stroke(width = s, cap = StrokeCap.Butt, join = StrokeJoin.Miter)

    drawPath(letterP(left, top, bottom, bar, w, s), MARK_INK, style = style)
    drawPath(letterF(left + w + gap, top, bottom, bar, w, s), MARK_INK, style = style)
    drawPath(letterP(left + 2 * (w + gap), top, bottom, bar, w, s), MARK_INK, style = style)
}

/** `P` — stem up the left, over the top, down the right, and back along the bar. One stroke. */
private fun letterP(l: Float, top: Float, bottom: Float, bar: Float, w: Float, s: Float) =
    Path().apply {
        moveTo(l + s / 2f, bottom)
        lineTo(l + s / 2f, top + s / 2f)
        lineTo(l + w - s / 2f, top + s / 2f)
        lineTo(l + w - s / 2f, bar)
        lineTo(l + s / 2f, bar)
    }

/** `F` — the same stem and top arm, plus a detached bar that stops short of the full width. */
private fun letterF(l: Float, top: Float, bottom: Float, bar: Float, w: Float, s: Float) =
    Path().apply {
        moveTo(l + s / 2f, bottom)
        lineTo(l + s / 2f, top + s / 2f)
        lineTo(l + w - s / 2f, top + s / 2f)
        moveTo(l + s / 2f, bar)
        lineTo(l + w * 0.80f, bar)
    }

/**
 * The game's title, centred under the mark. Sized in sp rather than as a fraction of the surface
 * so it still honours the user's font scale, and allowed two lines before it ellipsises — a long
 * title should wrap rather than shrink to nothing or run off the edge.
 */
private fun DrawScope.drawGameTitle(textMeasurer: TextMeasurer, gameTitle: String) {
    val maxWidth = (size.width * TITLE_WIDTH_FRACTION).toInt()
    val measured = textMeasurer.measure(
        text = gameTitle,
        style = TextStyle(
            color = TITLE_INK,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Light,
            fontSize = 24.sp,
            letterSpacing = 2.sp,
            textAlign = TextAlign.Center,
        ),
        constraints = Constraints(maxWidth = maxWidth),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    drawText(
        textLayoutResult = measured,
        topLeft = Offset(
            x = (size.width - measured.size.width) / 2f,
            y = size.height * TITLE_TOP_Y,
        ),
    )
}
