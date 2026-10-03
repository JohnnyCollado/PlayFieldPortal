package com.playfieldportal.studio.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import com.playfieldportal.studio.io.VideoCodecs
import com.playfieldportal.themekit.MotionCrop
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/*
 * Live motion wallpaper: the staged MP4 decoded with JCodec (pure Java, no native code) off the UI
 * thread, framed with the same crop the launcher plays with. Small, separately testable parts:
 *   MotionScheduler  - pure timing + the "can it keep up" verdict
 *   MotionEngine     - the pacing loop, written against an injected source and clock
 *   ReadAheadSource  - JCodec decoding a few frames ahead on its own thread, so decoding one frame
 *                      overlaps converting the previous one (a bounded ring of YUV copies)
 *   FrameConverter   - YUV -> preview-sized bitmap in one pass, into buffers reused every frame
 *   FrameMailbox     - hands frames to the display, which shows each on the first vsync it is due
 * At most four YUV copies and the few preview-sized bitmaps in flight are alive; nothing
 * source-sized is allocated per frame.
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

/**
 * A frame the decoder has produced; [render] is the (optional, per-frame) conversion to pixels.
 * [recycle] hands back whatever buffer the frame borrowed; the engine calls it once it is done with
 * the frame, shown or dropped.
 */
interface DecodedFrame<T> {
    val ptsMs: Long
    val durationMs: Long
    fun render(): T
    fun recycle() {}
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

/** The pacing loop. Pure with respect to its collaborators, so tests drive it with a fake source and clock. */
object MotionEngine {

    /**
     * Plays [open]'s source on [decodeContext] until it ends ([loop] false), [maxPlayMs] of the clip
     * has played, [shouldStop] says so, the caller cancels, or [scheduler] gives up. Each shown frame
     * goes to [publish] with the clock time it is due, handed over [leadMs] before that time (0 =
     * exactly on time); the loop holds one decoded frame at a time.
     */
    suspend fun <T> run(
        open: () -> MotionFrameSource<T>?,
        scheduler: (sourceFps: Float) -> MotionScheduler,
        clock: MotionClock,
        decodeContext: CoroutineContext,
        loop: Boolean,
        maxPlayMs: Long = Long.MAX_VALUE,
        leadMs: Long = 0L,
        shouldStop: () -> Boolean = { false },
        publish: (dueMs: Long, frame: T) -> Unit,
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
                loopFrames(source, scheduler(source.fps), clock, loop, maxPlayMs, leadMs, shouldStop, publish)
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
        leadMs: Long,
        shouldStop: () -> Boolean,
        publish: (dueMs: Long, frame: T) -> Unit,
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
            try {
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
                        if (due - leadMs > now) clock.sleep(due - leadMs - now)
                        publish(due, image)
                        scheduler.recordPublished()
                        lastPublishAt = clock.nowMs()
                    }
                }
            } finally {
                frame.recycle()
            }
            if (scheduler.verdict(clock.nowMs()) == MotionScheduler.Verdict.FALL_BACK) return MotionOutcome.FELL_BACK
        }
    }
}

/** An axis-aligned region in 0..1 video coordinates. */
data class NormRect(val left: Float, val top: Float, val width: Float, val height: Float)

/** A pixel rectangle, [right] and [bottom] exclusive. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

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

    /** The pixels of a [srcW] x [srcH] frame that [visibleRegion] covers, at least one each way. */
    fun sourceRect(srcW: Int, srcH: Int, outW: Int, outH: Int, crop: MotionCrop?): PixelRect {
        val r = visibleRegion(srcW.toFloat(), srcH.toFloat(), outW.toFloat(), outH.toFloat(), crop)
        val left = (r.left * srcW).roundToInt().coerceIn(0, srcW - 1)
        val top = (r.top * srcH).roundToInt().coerceIn(0, srcH - 1)
        return PixelRect(
            left = left,
            top = top,
            right = ((r.left + r.width) * srcW).roundToInt().coerceIn(left + 1, srcW),
            bottom = ((r.top + r.height) * srcH).roundToInt().coerceIn(top + 1, srcH),
        )
    }
}

/**
 * One decoded picture -> the preview's opaque [outW] x [outH] bitmap, framed by [crop]. Every buffer
 * is allocated once and reused, so a frame costs no Java heap (converting through JCodec's AWTUtil
 * allocated ~14 MB per 1080p frame, all of it humongous to G1) and one native copy into the bitmap.
 *
 * The pixels match that AWTUtil path exactly: the YUV -> RGB step is JCodec's own
 * `Yuv420pToRgb.YUV420pToRGBN2N` integer maths (BT.601 limited range, 2x2 nearest chroma), written
 * into the same `TYPE_3BYTE_BGR` layout `AWTUtil.toBufferedImage` produces, and Java2D then does the
 * same bilinear crop-and-scale. Only the source rectangle the view shows (plus the one-pixel ring
 * bilinear may sample) is converted. Not thread-safe — the engine thread owns it.
 */
internal class FrameConverter(private val crop: MotionCrop?, private val outW: Int, private val outH: Int) {
    private var source: BufferedImage? = null
    private val scaled = BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB)
    private val scaledPixels = (scaled.raster.dataBuffer as DataBufferInt).data
    private val bytes = ByteArray(outW * outH * 4)

    // Little-endian ARGB ints are B, G, R, A in memory: exactly Skia's BGRA_8888.
    private val bytesAsInts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
    private val info = ImageInfo(outW, outH, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)

    fun convert(planes: VideoCodecs.YuvPlanes): ImageBitmap {
        val w = planes.width
        val h = planes.height
        val src = source?.takeIf { it.width == w && it.height == h }
            ?: BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR).also { source = it }
        val r = MotionFraming.sourceRect(w, h, outW, outH, crop)
        toBgr(
            planes, (src.raster.dataBuffer as DataBufferByte).data, w,
            x0 = (r.left - 1).coerceAtLeast(0), y0 = (r.top - 1).coerceAtLeast(0),
            x1 = (r.right + 1).coerceAtMost(w), y1 = (r.bottom + 1).coerceAtMost(h),
        )
        val g = scaled.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src, 0, 0, outW, outH, r.left, r.top, r.right, r.bottom, null)
        } finally {
            g.dispose()
        }
        bytesAsInts.clear()
        bytesAsInts.put(scaledPixels)
        // installPixels copies into native memory, so [bytes] is free for the next frame at once;
        // immutable, the bitmap's pixels are shared with (not copied into) each Skia image drawn from it.
        val bitmap = Bitmap()
        check(bitmap.installPixels(info, bytes, outW * 4)) { "Skia refused a ${outW}x$outH frame" }
        bitmap.setImmutable()
        return bitmap.asComposeImageBitmap()
    }

    private fun toBgr(p: VideoCodecs.YuvPlanes, bgr: ByteArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        val yPlane = p.y
        val uPlane = p.u
        val vPlane = p.v
        for (row in y0 until y1) {
            val sy = row + p.cropY
            val yOff = sy * p.yStride
            val cOff = (sy shr 1) * p.cStride
            var o = (row * w + x0) * 3
            for (col in x0 until x1) {
                val sx = col + p.cropX
                val c = 298 * (yPlane[yOff + sx] + 112)
                val cb = uPlane[cOff + (sx shr 1)].toInt()
                val cr = vPlane[cOff + (sx shr 1)].toInt()
                bgr[o] = ((c + 516 * cb + 128) shr 8).coerceIn(0, 255).toByte()
                bgr[o + 1] = ((c - 100 * cb - 208 * cr + 128) shr 8).coerceIn(0, 255).toByte()
                bgr[o + 2] = ((c + 409 * cr + 128) shr 8).coerceIn(0, 255).toByte()
                o += 3
            }
        }
    }
}

/**
 * JCodec behind [MotionFrameSource], decoding ahead on its own thread: while the engine converts and
 * paces frame N, frames N+1.. decode, so a frame costs max(decode, convert) instead of their sum.
 * JCodec decodes into one per-thread buffer, so each frame is copied into one of [SLOTS] reusable
 * [VideoCodecs.YuvPlanes] — the bounded ring; when the engine falls behind, the reader blocks on it.
 * Looping, the reader seeks back to frame 0 the moment it reaches the end, so the next pass is
 * already decoding before the engine asks for it (no hitch at the loop point).
 */
internal class ReadAheadSource(
    private val reader: VideoCodecs.FrameReader,
    private val loop: Boolean,
    crop: MotionCrop?,
    outW: Int,
    outH: Int,
) : MotionFrameSource<ImageBitmap> {
    override val fps: Float = reader.fps

    private val converter = FrameConverter(crop, outW, outH)
    private val free = ArrayBlockingQueue<VideoCodecs.YuvPlanes>(SLOTS).apply { repeat(SLOTS) { add(VideoCodecs.YuvPlanes()) } }

    /** Decoded frames in order, [END] at each end of the stream, or the Throwable the reader died of. */
    private val ready = ArrayBlockingQueue<Any>(SLOTS + 1)

    @Volatile private var closed = false

    private val thread = Thread(::readAhead, READ_THREAD).apply {
        isDaemon = true
        start()
    }

    private fun readAhead() {
        try {
            while (!closed) {
                val slot = free.take()
                val raw = reader.next()
                if (raw == null) {
                    free.put(slot)
                    ready.put(END)
                    if (!loop) return
                    reader.rewind()
                    continue
                }
                raw.copyInto(slot)
                ready.put(slot)
            }
        } catch (t: Throwable) {
            // Closing interrupts the reader mid-wait (or mid-read); anything else is a decoder that gave up.
            if (!closed && t !is InterruptedException) runCatching { ready.put(t) }
        } finally {
            reader.close()
        }
    }

    override fun decode(): DecodedFrame<ImageBitmap>? = when (val item = ready.take()) {
        END -> null
        is Throwable -> throw IOException("the motion decoder stopped", item)
        else -> {
            val planes = item as VideoCodecs.YuvPlanes
            object : DecodedFrame<ImageBitmap> {
                override val ptsMs = planes.ptsMs
                override val durationMs = planes.durationMs
                override fun render(): ImageBitmap = converter.convert(planes)
                override fun recycle() {
                    free.offer(planes)
                }
            }
        }
    }

    /** Nothing to do: the reader already restarted the clip when it reached the end. */
    override fun rewind() = Unit

    /** Stops the reader and waits for it to let go of the file, so a clip is never held open after playback. */
    override fun close() {
        closed = true
        thread.interrupt()
        thread.join(CLOSE_WAIT_MS)
    }

    private companion object {
        const val SLOTS = 4
        const val CLOSE_WAIT_MS = 2_000L
        val END = Any()
        const val READ_THREAD = "${MotionPlayer.DECODE_THREAD}-read"
    }
}

/**
 * Frames handed from the decoder to the display. The decoder posts each frame a little ahead of its
 * due time; every display frame takes the newest one that is due, so a frame appears on the first
 * vsync at or after its time however late the decode thread's own timer woke (Windows parks in
 * whole timer ticks). Older due frames are superseded, never shown late.
 */
internal class FrameMailbox<T : Any>(private val capacity: Int = 3) {
    private val frames = ArrayDeque<Pair<Long, T>>()

    @Synchronized
    fun offer(dueMs: Long, frame: T) {
        if (frames.size == capacity) frames.removeFirst()
        frames.addLast(dueMs to frame)
    }

    @Synchronized
    fun takeDue(nowMs: Long): T? {
        var due: T? = null
        while (frames.isNotEmpty() && frames.first().first <= nowMs) due = frames.removeFirst().second
        return due
    }

    @Synchronized
    fun isEmpty(): Boolean = frames.isEmpty()
}

object MotionPlayer {
    /** The preview's 16:9 frame buffer; the 832x468 view is exactly this aspect. */
    const val OUT_WIDTH = 640
    const val OUT_HEIGHT = 360

    /**
     * How early a frame is handed to the display: enough to absorb the pacing thread oversleeping (a
     * timer tick on Windows is ~15.6 ms), so the frame is already waiting when its vsync comes.
     */
    const val PRESENT_LEAD_MS = 30L

    /**
     * Pacing and conversion run here and nowhere near the UI: one daemon thread, so a slow clip can
     * only ever starve itself (each source decodes on its own read-ahead thread). Blocking sleeps are
     * not used (the loop suspends), so the thread is free between frames.
     */
    val decodeDispatcher: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, DECODE_THREAD).apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
    const val DECODE_THREAD = "studio-motion-decode"

    /**
     * Plays [file] at [speed], framed by [crop], calling [onFrame] from the decode thread with each
     * frame to show and the [SystemMotionClock] time it is due ([PRESENT_LEAD_MS] ahead of that).
     * Returns how it ended; cancel the calling coroutine to stop it.
     */
    suspend fun play(
        file: java.io.File,
        crop: MotionCrop?,
        speed: Float,
        loop: Boolean,
        maxPlayMs: Long = Long.MAX_VALUE,
        onFrame: (dueMs: Long, frame: ImageBitmap) -> Unit,
    ): MotionOutcome = MotionEngine.run(
        open = {
            VideoCodecs.openFrames(file)?.let { ReadAheadSource(it, loop, crop, OUT_WIDTH, OUT_HEIGHT) }
        },
        scheduler = { fps -> MotionScheduler(speed, fps) },
        clock = SystemMotionClock,
        decodeContext = decodeDispatcher,
        loop = loop,
        maxPlayMs = maxPlayMs,
        leadMs = PRESENT_LEAD_MS,
        publish = onFrame,
    )

    /**
     * [play], with each frame passed to [show] on the composition's frame clock, on the first vsync
     * at or after its due time. Call it from a composition effect. A clip that ends still shows its
     * last frames before this returns.
     */
    suspend fun present(
        file: java.io.File,
        crop: MotionCrop?,
        speed: Float,
        loop: Boolean,
        maxPlayMs: Long = Long.MAX_VALUE,
        show: (ImageBitmap) -> Unit,
    ): MotionOutcome = coroutineScope {
        val mailbox = FrameMailbox<ImageBitmap>()
        var decoding = true
        val presenter = launch {
            while (decoding || !mailbox.isEmpty()) {
                withFrameNanos { mailbox.takeDue(SystemMotionClock.nowMs())?.let(show) }
            }
        }
        val outcome = play(file, crop, speed, loop, maxPlayMs) { due, frame -> mailbox.offer(due, frame) }
        decoding = false
        if (outcome == MotionOutcome.ENDED) presenter.join() else presenter.cancel()
        outcome
    }
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
        val outcome = MotionPlayer.present(file, crop, speed, loop = true) { frame = it }
        if (outcome != MotionOutcome.ENDED) {
            frame = null
            live.motionFellBack = true
        }
    }
    // Reading the frame inside the draw lambda: a new frame redraws, it never recomposes. Its own
    // layer keeps that redraw to this canvas instead of re-recording the whole preview frame.
    Canvas(modifier.fillMaxSize().graphicsLayer()) { frame?.let { drawFrame(it) } }
}
