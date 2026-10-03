package com.playfieldportal.studio.preview

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.UiMediaLimits
import java.io.File
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/*
 * Boot and GameBoot playback in the preview. A theme's own clip plays full-frame over the preview
 * through the same JCodec frame loop as the motion wallpaper (no audio: A10 — JCodec decodes
 * video only). The built-in sequences are recreated from the launcher's drawing code:
 *   Boot      - feature-xmb BootSequenceOverlay: logo eases in over the wave, holds, dissolves
 *   GameBoot  - feature-xmb GameBootSequence: white field, PFP mark, bloom sweeps keyed to the
 *               launch sound's measured loudness, 5.0 s
 */

enum class BootKind(val slotKey: String, val label: String) {
    BOOT("boot_video", "Boot"),
    GAMEBOOT("gameboot_video", "GameBoot"),
}

/** [kind] null = nothing playing. [clip] null = the built-in sequence for [kind]. */
data class BootPlaybackState(val kind: BootKind? = null, val clip: File? = null) {
    val isPlaying: Boolean get() = kind != null

    companion object {
        val IDLE = BootPlaybackState()
    }
}

/** The playback state machine — pure, so the toolbar buttons, the section's Play button and clear/new/open all share it. */
object BootPlayback {

    /** Starts [kind] (replacing whatever was playing). */
    fun play(kind: BootKind, clip: File?): BootPlaybackState = BootPlaybackState(kind, clip)

    /** The Play button's behaviour: pressing the one that is already playing stops it. */
    fun toggle(state: BootPlaybackState, kind: BootKind, clip: File?): BootPlaybackState =
        if (state.kind == kind) BootPlaybackState.IDLE else play(kind, clip)

    fun finish(): BootPlaybackState = BootPlaybackState.IDLE

    /**
     * The document changed under a running sequence (cleared, replaced, new, open): if the clip
     * being played is no longer the one the document holds for that kind, stop.
     */
    fun reconcile(state: BootPlaybackState, currentClip: (BootKind) -> File?): BootPlaybackState {
        val kind = state.kind ?: return state
        return if (state.clip == currentClip(kind)) state else BootPlaybackState.IDLE
    }

    /** How much of a custom clip plays: the same ceiling the launcher clips it to. */
    fun clipCapMs(kind: BootKind): Long = when (kind) {
        BootKind.BOOT -> UiMediaLimits.BOOT_MAX_MS
        BootKind.GAMEBOOT -> UiMediaLimits.GAMEBOOT_CLIP_MAX_MS
    }

    /** Length of the built-in sequence. */
    fun builtInMs(kind: BootKind): Long = when (kind) {
        BootKind.BOOT -> BootTimeline.BOOT_TOTAL_MS
        BootKind.GAMEBOOT -> GameBootTimeline.SEQUENCE_MS
    }

    /**
     * Whether the overlay hides the whole frame [elapsedMs] into a run: a clip (over black) and
     * GameBoot (its white field) always do; the built-in Boot does until its fade-out begins.
     */
    fun coversFrame(kind: BootKind, clip: File?, elapsedMs: Long): Boolean =
        clip != null || kind == BootKind.GAMEBOOT || elapsedMs < BootTimeline.FADE_OUT_START_MS
}

/** BootSequenceOverlay's timeline: scale up, then alpha up, hold, then the whole overlay fades out. */
object BootTimeline {
    const val FADE_IN_MS = 800L
    const val HOLD_MS = 1_400L
    const val FADE_OUT_MS = 600L
    const val FADE_OUT_START_MS = FADE_IN_MS * 2 + HOLD_MS
    const val BOOT_TOTAL_MS = FADE_OUT_START_MS + FADE_OUT_MS

    data class Frame(val logoScale: Float, val logoAlpha: Float, val overlayAlpha: Float)

    // tween() defaults to FastOutSlowInEasing, as in the launcher's Animatables.
    private fun ease(t: Float) = FastOutSlowInEasing.transform(t.coerceIn(0f, 1f))

    fun at(ms: Long): Frame {
        val scaleT = ms / FADE_IN_MS.toFloat()
        val alphaT = (ms - FADE_IN_MS) / FADE_IN_MS.toFloat()
        val fadeOutT = (ms - FADE_OUT_START_MS) / FADE_OUT_MS.toFloat()
        return Frame(
            logoScale = 0.92f + 0.08f * ease(scaleT),
            logoAlpha = ease(alphaT),
            overlayAlpha = 1f - ease(fadeOutT),
        )
    }
}

/** GameBootSequence's maths, ported verbatim (the loudness table is the launch sound's measured envelope). */
object GameBootTimeline {
    const val SEQUENCE_MS = UiMediaLimits.GAMEBOOT_SEQUENCE_MS

    private val LOUDNESS: List<Pair<Int, Float>> = listOf(
        0 to 0.00f, 350 to 0.00f, 900 to 0.06f, 1_400 to 0.26f, 1_700 to 0.47f,
        2_050 to 0.86f, 2_250 to 0.45f, 2_500 to 0.51f, 2_900 to 0.87f, 3_100 to 1.00f,
        3_300 to 0.89f, 3_700 to 0.57f, 3_950 to 0.39f, 4_250 to 0.80f, 4_400 to 0.49f,
        4_800 to 0.43f, 5_000 to 0.00f,
    )

    data class Sweep(val startMs: Int, val endMs: Int, val intensity: Float)

    val SWEEPS: List<Sweep> = listOf(
        Sweep(1_700, 2_400, 0.50f),
        Sweep(2_500, 3_500, 1.00f),
        Sweep(4_100, 4_500, 0.26f),
    )

    const val RAMP_START_MS = 900f
    const val RAMP_END_MS = 1_700f
    const val DECAY_START_MS = 4_400
    const val DECAY_MS = 600

    /** 0 while the screen is still black, 1 once the field is fully up. */
    fun rampAt(ms: Float): Float = ((ms - RAMP_START_MS) / (RAMP_END_MS - RAMP_START_MS)).coerceIn(0f, 1f)

    /** The black curtain coming back down over the last 600 ms. */
    fun sinkAt(ms: Float): Float = ((ms - DECAY_START_MS) / DECAY_MS).coerceIn(0f, 1f)

    fun loudnessAt(ms: Float): Float {
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

    fun Sweep.progressAt(ms: Float): Float? =
        if (ms < startMs || ms > endMs) null else (ms - startMs) / (endMs - startMs).toFloat()

    fun Sweep.amplitudeAt(ms: Float): Float {
        val p = progressAt(ms) ?: return 0f
        return loudnessAt(ms) * sin(PI.toFloat() * p) * intensity
    }
}

/**
 * Whether a playing boot overlay fully hides the preview frame beneath it, so the frame can skip
 * drawing what nobody sees. Pass it to [BootOverlay] and read [coversFrame] inside a draw lambda,
 * e.g. `Modifier.drawWithContent { if (!cover.coversFrame) drawContent() }`: it flips at most twice
 * a run, and each flip only redraws.
 */
@Stable
class BootCover {
    private var covering by mutableStateOf<State<Boolean>?>(null)

    val coversFrame: Boolean get() = covering?.value == true

    internal fun attach(state: State<Boolean>) {
        covering = state
    }

    internal fun detach(state: State<Boolean>) {
        if (covering === state) covering = null
    }
}

@Composable
fun rememberBootCover(): BootCover = remember { BootCover() }

// ── Composables ──────────────────────────────────────────────────────────────

private const val CLIP_HOLD_MS = 400L
private const val NOTE_HOLD_MS = 3_000L

/** A clip's black box and GameBoot's white field cover the frame for their whole run. */
private val ALWAYS_COVERS: State<Boolean> = mutableStateOf(true)

/**
 * Plays [playback] over the whole frame. A press anywhere (or any key, handled by the canvas) ends
 * it. [onFinished] fires exactly once per run: at the natural end, or when the viewer dismisses it.
 * [cover], when given, tracks whether the overlay currently hides the frame completely.
 */
@Composable
internal fun BootOverlay(
    playback: BootPlaybackState,
    model: XmbPreviewModel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    cover: BootCover? = null,
) {
    val kind = playback.kind ?: return
    val finish by rememberUpdatedState(onFinished)
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { finish() } },
    ) {
        val clip = playback.clip
        when {
            clip != null -> {
                ClipPlayback(kind, clip) { finish() }
                CoversFrameWhile(cover, ALWAYS_COVERS)
            }
            kind == BootKind.BOOT -> BuiltInBoot(model, cover) { finish() }
            else -> {
                BuiltInGameBoot(WaveMotion.paramsFor(model.waveStyle)) { finish() }
                CoversFrameWhile(cover, ALWAYS_COVERS)
            }
        }
    }
}

/** Points [cover] at [covering] while this is composed. */
@Composable
private fun CoversFrameWhile(cover: BootCover?, covering: State<Boolean>) {
    if (cover == null) return
    DisposableEffect(cover, covering) {
        cover.attach(covering)
        onDispose { cover.detach(covering) }
    }
}

/** Wall-clock ms since this composable started, ticking once per frame; [onDone] fires when [totalMs] has passed. */
@Composable
private fun rememberElapsedMs(totalMs: Long, onDone: () -> Unit): androidx.compose.runtime.LongState {
    val elapsed = remember { mutableLongStateOf(0L) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(totalMs) {
        var start = -1L
        while (true) {
            val finished = withFrameNanos { nanos ->
                if (start < 0) start = nanos
                val ms = ((nanos - start) / 1_000_000L).coerceAtMost(totalMs)
                elapsed.longValue = ms
                ms >= totalMs
            }
            if (finished) break
        }
        done()
    }
    return elapsed
}

@Composable
private fun BuiltInBoot(model: XmbPreviewModel, cover: BootCover?, onDone: () -> Unit) {
    val elapsed = rememberElapsedMs(BootTimeline.BOOT_TOTAL_MS, onDone)
    val covering = remember { derivedStateOf { BootPlayback.coversFrame(BootKind.BOOT, null, elapsed.longValue) } }
    CoversFrameWhile(cover, covering)
    // Decoded off the UI thread, once per session. The logo is fully transparent for its first
    // FADE_IN_MS anyway, so the first run's decode is never seen.
    val logo by produceState<ImageBitmap?>(null) { value = withContext(Dispatchers.IO) { BOOT_LOGO } }
    // The launcher's boot always runs the animated wave, whatever the theme's style.
    val params = remember { WaveMotion.paramsFor(PfpThemeManifest.WAVE_ANIMATED) }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = BootTimeline.at(elapsed.longValue).overlayAlpha },
        contentAlignment = Alignment.Center,
    ) {
        WaveBackground(model, params) { WaveMotion.timeSeconds(params, elapsed.longValue) }
        logo?.let {
            Image(
                bitmap = it,
                contentDescription = "Play Field Portal",
                modifier = Modifier.size(220.dp).graphicsLayer {
                    val frame = BootTimeline.at(elapsed.longValue)
                    alpha = frame.logoAlpha
                    scaleX = frame.logoScale
                    scaleY = frame.logoScale
                },
            )
        }
        Text(
            text = "Play Field Portal",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 4.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .graphicsLayer { alpha = BootTimeline.at(elapsed.longValue).logoAlpha },
        )
    }
}

/** A 1024 px WebP with alpha: decoding it is worth doing once, not on every run. */
private val BOOT_LOGO: ImageBitmap? by lazy {
    runCatching {
        BootTimeline::class.java.classLoader.getResourceAsStream("xmb/pfp_boot_logo.webp")?.use {
            SkiaImage.makeFromEncoded(it.readBytes()).toComposeImageBitmap()
        }
    }.getOrNull()
}

private val FIELD = Color.White
private val MARK_INK = Color(0xFF9A9AA4)
private val TITLE_INK = Color(0xFFA6A6AE)
private val BLOOM_WARM = Color(0xFFF6F05A)
private val BLOOM_COOL = Color(0xFF6EE8E0)
private val BLOOM_MID = Color(0xFFA8EC78)
private const val MARK_CENTER_Y = 0.40f
private const val MARK_HEIGHT_FRACTION = 0.18f
private const val TITLE_TOP_Y = 0.585f
private const val TITLE_WIDTH_FRACTION = 0.80f
private const val SAMPLE_GAME_TITLE = "Portal Quest"

@Composable
private fun BuiltInGameBoot(params: WaveParams, onDone: () -> Unit) {
    val elapsed = rememberElapsedMs(GameBootTimeline.SEQUENCE_MS, onDone)
    val measurer = rememberTextMeasurer()
    // GameBoot honours the wave style: frozen or reduced drops the blooms and keeps the crossfades.
    val reduced = !params.animated || params.ampScale < 1f
    // The mark and the title depend only on the size, so they are built once per size; the clock is
    // read only while drawing, and the node's own layer keeps each tick's redraw to this sequence.
    Spacer(
        Modifier.fillMaxSize().graphicsLayer().drawWithCache {
            val mark = pfpMark(size)
            val title = measureGameTitle(measurer, SAMPLE_GAME_TITLE, size)
            val titleTopLeft = Offset((size.width - title.size.width) / 2f, size.height * TITLE_TOP_Y)
            onDrawBehind {
                val ms = elapsed.longValue.toFloat()
                drawRect(FIELD)
                mark.letters.forEach { drawPath(it, MARK_INK, style = mark.stroke) }
                drawText(textLayoutResult = title, topLeft = titleTopLeft)
                if (!reduced) GameBootTimeline.SWEEPS.forEach { drawSweep(it, ms) }
                val curtain = 1f - GameBootTimeline.rampAt(ms)
                if (curtain > 0f) drawRect(Color.Black.copy(alpha = curtain))
                val sink = GameBootTimeline.sinkAt(ms)
                if (sink > 0f) drawRect(Color.Black.copy(alpha = sink))
            }
        },
    )
}

private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

private fun DrawScope.drawSweep(sweep: GameBootTimeline.Sweep, ms: Float) {
    val amp = with(GameBootTimeline) { sweep.amplitudeAt(ms) }
    if (amp <= 0.002f) return
    val progress = with(GameBootTimeline) { sweep.progressAt(ms) } ?: return
    val travelled = smoothstep(progress)
    val cx = size.width * (0.92f - 0.50f * travelled)
    val cy = size.height * MARK_CENTER_Y
    bloom(BLOOM_WARM, cx, cy, size.width * 0.55f, 0.55f * amp, BlendMode.Multiply)
    bloom(BLOOM_COOL, cx - size.width * 0.30f, cy + size.height * 0.05f, size.width * 0.48f, 0.50f * amp, BlendMode.Multiply)
    bloom(BLOOM_MID, cx + size.width * 0.22f, cy - size.height * 0.12f, size.width * 0.26f, 0.30f * amp, BlendMode.Multiply)
    bloom(Color.White, cx + size.width * 0.10f, cy - size.height * 0.18f, size.width * 0.10f, 0.85f * amp, BlendMode.Plus)
    bloom(Color.White, cx + size.width * 0.19f, cy - size.height * 0.08f, size.width * 0.045f, 0.70f * amp, BlendMode.Plus)
}

private fun DrawScope.bloom(color: Color, cx: Float, cy: Float, radius: Float, alpha: Float, blendMode: BlendMode) {
    if (alpha <= 0.002f) return
    // Only the gradient's own square is filled: beyond its radius it is fully transparent, which
    // leaves the field unchanged under both Multiply and Plus, so the rest of the canvas was pure fill cost.
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
        topLeft = Offset(cx - radius, cy - radius),
        size = Size(radius * 2f, radius * 2f),
        blendMode = blendMode,
    )
}

/** The PFP mark's three letters at [size], and the stroke they are drawn with. */
private class PfpMark(val letters: List<Path>, val stroke: Stroke)

private fun pfpMark(size: Size): PfpMark {
    val h = size.height * MARK_HEIGHT_FRACTION
    val s = h * 0.085f
    val w = h * 0.62f
    val gap = h * 0.30f
    val left = (size.width - (3 * w + 2 * gap)) / 2f
    val cy = size.height * MARK_CENTER_Y
    val top = cy - h / 2f
    val bottom = cy + h / 2f
    val bar = cy - h * 0.04f
    return PfpMark(
        letters = listOf(
            letterP(left, top, bottom, bar, w, s),
            letterF(left + w + gap, top, bottom, bar, w, s),
            letterP(left + 2 * (w + gap), top, bottom, bar, w, s),
        ),
        stroke = Stroke(width = s, cap = StrokeCap.Butt, join = StrokeJoin.Miter),
    )
}

private fun letterP(l: Float, top: Float, bottom: Float, bar: Float, w: Float, s: Float) = Path().apply {
    moveTo(l + s / 2f, bottom)
    lineTo(l + s / 2f, top + s / 2f)
    lineTo(l + w - s / 2f, top + s / 2f)
    lineTo(l + w - s / 2f, bar)
    lineTo(l + s / 2f, bar)
}

private fun letterF(l: Float, top: Float, bottom: Float, bar: Float, w: Float, s: Float) = Path().apply {
    moveTo(l + s / 2f, bottom)
    lineTo(l + s / 2f, top + s / 2f)
    lineTo(l + w - s / 2f, top + s / 2f)
    moveTo(l + s / 2f, bar)
    lineTo(l + w * 0.80f, bar)
}

private fun measureGameTitle(textMeasurer: TextMeasurer, gameTitle: String, size: Size): TextLayoutResult =
    textMeasurer.measure(
        text = gameTitle,
        style = TextStyle(
            color = TITLE_INK,
            fontWeight = FontWeight.Light,
            fontSize = 24.sp,
            letterSpacing = 2.sp,
            textAlign = TextAlign.Center,
        ),
        constraints = Constraints(maxWidth = (size.width * TITLE_WIDTH_FRACTION).toInt()),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )

/** A theme's own boot / GameBoot clip, full-frame, played once. */
@Composable
private fun ClipPlayback(kind: BootKind, clip: File, onDone: () -> Unit) {
    var frame by remember(clip) { mutableStateOf<ImageBitmap?>(null) }
    var note by remember(clip) { mutableStateOf<String?>(null) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(clip) {
        when (MotionPlayer.present(clip, crop = null, speed = 1f, loop = false, maxPlayMs = BootPlayback.clipCapMs(kind)) { frame = it }) {
            MotionOutcome.ENDED -> delay(CLIP_HOLD_MS)
            MotionOutcome.FELL_BACK -> {
                note = "The preview can't play this clip smoothly — it plays on the device"
                delay(NOTE_HOLD_MS)
            }
            MotionOutcome.FAILED -> {
                note = "The preview can't decode this clip — it plays on the device"
                delay(NOTE_HOLD_MS)
            }
        }
        done()
    }
    // Its own layer: each new frame redraws this box, not the preview frame underneath.
    Box(Modifier.fillMaxSize().graphicsLayer().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) { frame?.let { drawFrame(it) } }
        note?.let { PlaysOnDeviceBadge(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)) }
    }
}

/** Draws [frame] stretched over the whole canvas (it is already framed to the preview's aspect). */
internal fun DrawScope.drawFrame(frame: ImageBitmap) {
    drawImage(frame, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
}

/** The small "this only plays on the device" note shown when the preview falls back to a still. */
@Composable
internal fun PlaysOnDeviceBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = modifier
            .background(Color(0xB3000000), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
