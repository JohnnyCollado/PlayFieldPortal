package com.playfieldportal.studio.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.playfieldportal.studio.io.VideoCodecs
import com.playfieldportal.themekit.MotionCrop
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo

/*
 * Live motion wallpaper: the staged MP4 decoded with JCodec (pure Java, no native code) on a
 * dedicated background thread, one frame at a time, framed with the same crop the launcher plays
 * with. Three small, separately testable parts:
 *   MotionScheduler  - pure timing + the "can it keep up" verdict
 *   MotionEngine     - the decode loop, written against an injected source and clock
 *   MotionPlayer     - the production wiring (JCodec source, decode thread, bitmap conversion)
 * Never more than the frame being decoded and the one on screen are alive; nothing is buffered
 * ahead, so a 1080p theme costs two preview-sized bitmaps, not a ring of source-sized ones.
 */

/** Below this the preview shows the poster instead; see [MotionScheduler.requiredFps]. */
data class FallbackPolicy(
    /** Absolute floor, in published frames per second, once the clip is playing. */
    val minFps: Float = 12f,
    /** The share of the clip's own (speed-scaled) rate that must be delivered, for slow clips. */
    val keepUpShare: Float = 0.8f,
    /** JCodec's first frames include class loading and JIT warm-up; they are not judged. */
    val warmupMs: Long = 1_500L,
    /** The span each verdict averages over. */
    val windowMs: Long = 2_000L,
)

enum class MotionOutcome { ENDED, FELL_BACK, FAILED }

/** A frame the decoder has produced; [render] is the (optional, per-frame) conversion to pixels. */
interface DecodedFrame<T> {
    val ptsMs: Long
    val durationMs: Long
    fun render(): T
}

interface MotionFrameSource<T> : AutoCloseable {
    val fps: Float

    /** The next frame in presentation order, or null at the end of the stream. */
    fun decode(): DecodedFrame<T>?
    fun rewind()
}

interface MotionClock {
    fun nowMs(): Long
    suspend fun sleep(ms: Long)
}

object SystemMotionClock : MotionClock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000L
    override suspend fun sleep(ms: Long) = delay(ms)
}

/**
 * Pure frame pacing for a clip played at [speed] (Reduced = 0.5). Frame `pts` maps to wall time
 * `pts / speed`; a frame that is late by more than two of its own intervals is dropped (decoded,
 * not shown) so the picture catches up, unless nothing has been shown for [STARVED_MS] — then the
 * late frame is shown anyway, because a slow picture beats a frozen one and the fps verdict, not
 * the drop rule, is what decides to give up.
 */
class MotionScheduler(
    val speed: Float,
    val sourceFps: Float,
    private val policy: FallbackPolicy = FallbackPolicy(),
) {
    enum class Action { WAIT, SHOW, DROP }
    enum class Verdict { OK, FALL_BACK }

    /** What "keeping up" means for this clip: the floor, or most of the clip's own rate if that is lower. */
    val requiredFps: Float = min(policy.minFps, sourceFps * speed * policy.keepUpShare)

    private var startMs = 0L
    private var windowStartMs = 0L
    private var publishedInWindow = 0

    fun wallMs(ptsMs: Long): Long = (ptsMs / speed).roundToLong()

    fun action(nowMs: Long, dueMs: Long, frameDurationMs: Long, sinceLastPublishMs: Long): Action = when {
        dueMs > nowMs -> Action.WAIT
        nowMs - dueMs > 2 * wallMs(frameDurationMs) && sinceLastPublishMs < STARVED_MS -> Action.DROP
        else -> Action.SHOW
    }

    fun begin(nowMs: Long) {
        startMs = nowMs
        windowStartMs = nowMs + policy.warmupMs
        publishedInWindow = 0
    }

    fun recordPublished() {
        publishedInWindow++
    }

    /** Judged once per window, after the warm-up: delivered fps below [requiredFps] means give up. */
    fun verdict(nowMs: Long): Verdict {
        if (nowMs < windowStartMs + policy.windowMs) return Verdict.OK
        val fps = publishedInWindow * 1000f / (nowMs - windowStartMs)
        windowStartMs = nowMs
        publishedInWindow = 0
        return if (fps < requiredFps) Verdict.FALL_BACK else Verdict.OK
    }

    companion object {
        /** After a stall longer than this the loop re-bases its timeline instead of fast-forwarding. */
        const val RESYNC_MS = 1_000L
        const val STARVED_MS = 250L

        /** Frozen styles never ask for a frame, and neither does a clip under a covering layer. */
        fun requestsFrames(params: WaveParams, covered: Boolean = false): Boolean = params.animated && !covered
    }
}

/** The decode loop. Pure with respect to its collaborators, so tests drive it with a fake source and clock. */
object MotionEngine {

    /**
     * Plays [open]'s source on [decodeContext] until it ends ([loop] false), [maxPlayMs] of the clip
     * has played, [shouldStop] says so, the caller cancels, or [scheduler] gives up. Each shown frame
     * goes to [publish]; the loop holds one decoded frame at a time.
     */
    suspend fun <T> run(
        open: () -> MotionFrameSource<T>?,
        scheduler: (sourceFps: Float) -> MotionScheduler,
        clock: MotionClock,
        decodeContext: CoroutineContext,
        loop: Boolean,
        maxPlayMs: Long = Long.MAX_VALUE,
        shouldStop: () -> Boolean = { false },
        publish: (T) -> Unit,
    ): MotionOutcome = withContext(decodeContext) {
        val source = try {
            open()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return@withContext MotionOutcome.FAILED
        source.use {
            try {
                loopFrames(source, scheduler(source.fps), clock, loop, maxPlayMs, shouldStop, publish)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A decoder that threw (corrupt stream, vanished file) is one that gave up.
                MotionOutcome.FAILED
            }
        }
    }

    private suspend fun <T> loopFrames(
        source: MotionFrameSource<T>,
        scheduler: MotionScheduler,
        clock: MotionClock,
        loop: Boolean,
        maxPlayMs: Long,
        shouldStop: () -> Boolean,
        publish: (T) -> Unit,
    ): MotionOutcome {
        var loopStart = clock.nowMs()
        var lastPublishAt = loopStart
        var loopEndPts = 0L
        scheduler.begin(loopStart)
        while (true) {
            coroutineContext.ensureActive()
            if (shouldStop()) return MotionOutcome.ENDED
            val frame = source.decode()
            if (frame == null) {
                // End of stream: the next pass starts where this one's timeline ended.
                if (!loop) return MotionOutcome.ENDED
                if (loopEndPts <= 0L) return MotionOutcome.FAILED
                loopStart += scheduler.wallMs(loopEndPts)
                loopEndPts = 0L
                source.rewind()
                continue
            }
            if (frame.ptsMs >= maxPlayMs) return MotionOutcome.ENDED
            loopEndPts = frame.ptsMs + frame.durationMs

            var now = clock.nowMs()
            var due = loopStart + scheduler.wallMs(frame.ptsMs)
            if (now - due > MotionScheduler.RESYNC_MS) {
                loopStart += now - due
                due = now
            }
            when (scheduler.action(now, due, frame.durationMs, now - lastPublishAt)) {
                MotionScheduler.Action.DROP -> Unit
                MotionScheduler.Action.WAIT, MotionScheduler.Action.SHOW -> {
                    // Convert first, then wait out what is left: the conversion is part of the frame's budget.
                    val image = frame.render()
                    now = clock.nowMs()
                    if (due > now) clock.sleep(due - now)
                    publish(image)
                    scheduler.recordPublished()
                    lastPublishAt = clock.nowMs()
                }
            }
            if (scheduler.verdict(clock.nowMs()) == MotionScheduler.Verdict.FALL_BACK) return MotionOutcome.FELL_BACK
        }
    }
}

/** An axis-aligned region in 0..1 video coordinates. */
data class NormRect(val left: Float, val top: Float, val width: Float, val height: Float)

/** Which part of the video a view shows — the launcher's `motionTransform`, expressed as a source rectangle. */
object MotionFraming {

    /**
     * core-ui `motionTransform` cover-fits the crop region into the view, clamping the overflow so the
     * frame always covers it. The view then shows `viewW / drawnW` of the video, offset by the
     * (clamped) translation; a null crop is the legacy centre-crop.
     */
    fun visibleRegion(videoW: Float, videoH: Float, viewW: Float, viewH: Float, crop: MotionCrop?): NormRect {
        val regionW = videoW * (crop?.w ?: 1f)
        val regionH = videoH * (crop?.h ?: 1f)
        val scale = max(viewW / regionW, viewH / regionH)
        val drawnW = videoW * scale
        val drawnH = videoH * scale
        var tx = 0f
        var ty = 0f
        if (crop != null) {
            val maxTx = max(0f, (drawnW - viewW) / 2f)
            val maxTy = max(0f, (drawnH - viewH) / 2f)
            tx = ((0.5f - (crop.x + crop.w / 2f)) * drawnW).coerceIn(-maxTx, maxTx)
            ty = ((0.5f - (crop.y + crop.h / 2f)) * drawnH).coerceIn(-maxTy, maxTy)
        }
        return NormRect(
            left = ((drawnW - viewW) / 2f - tx) / drawnW,
            top = ((drawnH - viewH) / 2f - ty) / drawnH,
            width = viewW / drawnW,
            height = viewH / drawnH,
        )
    }

    /** Crops [src] to the visible region and scales it to exactly [outW] x [outH], as an opaque bitmap. */
    fun render(src: BufferedImage, crop: MotionCrop?, outW: Int, outH: Int): ImageBitmap {
        val r = visibleRegion(src.width.toFloat(), src.height.toFloat(), outW.toFloat(), outH.toFloat(), crop)
        val sx1 = (r.left * src.width).roundToInt().coerceIn(0, src.width - 1)
        val sy1 = (r.top * src.height).roundToInt().coerceIn(0, src.height - 1)
        val sx2 = ((r.left + r.width) * src.width).roundToInt().coerceIn(sx1 + 1, src.width)
        val sy2 = ((r.top + r.height) * src.height).roundToInt().coerceIn(sy1 + 1, src.height)
        val out = BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src, 0, 0, outW, outH, sx1, sy1, sx2, sy2, null)
        } finally {
            g.dispose()
        }
        val pixels = (out.raster.dataBuffer as DataBufferInt).data
        // Little-endian ARGB ints are B, G, R, A in memory: exactly Skia's BGRA_8888.
        val bytes = ByteArray(outW * outH * 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels)
        val info = ImageInfo(outW, outH, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        return SkiaImage.makeRaster(info, bytes, outW * 4).toComposeImageBitmap()
    }
}

/** JCodec behind [MotionFrameSource]: frames are framed to the preview's 16:9 buffer only when shown. */
internal class JcodecMotionSource(
    private val reader: VideoCodecs.FrameReader,
    private val crop: MotionCrop?,
    private val outW: Int,
    private val outH: Int,
) : MotionFrameSource<ImageBitmap> {
    override val fps: Float get() = reader.fps

    override fun decode(): DecodedFrame<ImageBitmap>? {
        val raw = reader.next() ?: return null
        return object : DecodedFrame<ImageBitmap> {
            override val ptsMs = raw.ptsMs
            override val durationMs = raw.durationMs
            override fun render(): ImageBitmap = MotionFraming.render(raw.toImage(), crop, outW, outH)
        }
    }

    override fun rewind() = reader.rewind()
    override fun close() = reader.close()
}

object MotionPlayer {
    /** The preview's 16:9 frame buffer; the 832x468 view is exactly this aspect. */
    const val OUT_WIDTH = 640
    const val OUT_HEIGHT = 360

    /**
     * Every decode runs here and nowhere near the UI: one daemon thread, so a slow clip can only ever
     * starve itself. Blocking sleeps are not used (the loop suspends), so the thread is free between frames.
     */
    val decodeDispatcher: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, DECODE_THREAD).apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }.asCoroutineDispatcher()
    }
    const val DECODE_THREAD = "studio-motion-decode"

    /**
     * Plays [file] at [speed], framed by [crop], calling [onFrame] from the decode thread with each
     * frame to show. Returns how it ended; cancel the calling coroutine to stop it.
     */
    suspend fun play(
        file: java.io.File,
        crop: MotionCrop?,
        speed: Float,
        loop: Boolean,
        maxPlayMs: Long = Long.MAX_VALUE,
        onFrame: (ImageBitmap) -> Unit,
    ): MotionOutcome = MotionEngine.run(
        open = {
            VideoCodecs.openFrames(file)?.let { JcodecMotionSource(it, crop, OUT_WIDTH, OUT_HEIGHT) }
        },
        scheduler = { fps -> MotionScheduler(speed, fps) },
        clock = SystemMotionClock,
        decodeContext = decodeDispatcher,
        loop = loop,
        maxPlayMs = maxPlayMs,
        publish = onFrame,
    )
}

/**
 * The motion loop drawn over the poster. Frozen styles (and a boot sequence covering the frame)
 * request no frames at all, so the poster simply shows. If the decode cannot keep up the loop ends,
 * [PreviewLive.motionFellBack] raises the badge and the poster stays.
 */
@Composable
internal fun MotionWallpaperLayer(live: PreviewLive, file: java.io.File, modifier: Modifier = Modifier) {
    val crop = live.spec.motionCrop
    val speed = live.wave.speed
    val requests = MotionScheduler.requestsFrames(live.wave, covered = live.spec.boot.isPlaying)
    // The still frame resets whenever the style flips or the clip / crop changes.
    var frame by remember(file, crop, live.wave.animated) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file, crop, speed, requests) {
        live.motionFellBack = false
        if (!requests) return@LaunchedEffect
        val outcome = MotionPlayer.play(file, crop, speed, loop = true) { frame = it }
        if (outcome != MotionOutcome.ENDED) {
            frame = null
            live.motionFellBack = true
        }
    }
    // Reading the frame inside the draw lambda: a new frame redraws, it never recomposes.
    Canvas(modifier.fillMaxSize()) { frame?.let { drawFrame(it) } }
}
