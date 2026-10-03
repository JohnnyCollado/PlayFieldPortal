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
import com.playfieldportal.themekit.MotionCrop
import java.io.IOException
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
 * Live motion wallpaper, boot and GameBoot clips: decoded by FFmpeg off the UI thread and framed
 * with the same crop the launcher plays with. Small, separately testable parts:
 *   MotionScheduler   - pure timing + the "can it keep up" verdict
 *   MotionEngine      - the pacing loop, written against an injected source and clock
 *   FfmpegFrameReader - FFmpeg decoding on every core, cropping and scaling to the preview's size
 *   ReadAheadSource   - the reader running a few frames ahead on its own thread (a bounded ring of
 *                       preview-sized BGRA frames)
 *   FrameBitmaps      - BGRA -> Skia bitmap, through a fixed ring reused lap after lap
 *   FrameMailbox      - hands frames to the display, which shows each on the first vsync it is due
 * Nothing source-sized is held in Java, and no pixel buffer is allocated per frame.
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

    /**
     * Whether the frame after the one just handed out is already decoded. Dropping a late frame
     * saves only its conversion, so it is worth doing only when there is a newer frame to skip to.
     */
    val nextReady: Boolean get() = false

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
 * not shown) so the picture catches up — but only when a newer frame is already decoded. Every
 * frame has to be decoded anyway (H.264 frames depend on their predecessors), so with the decoder
 * behind, dropping skips nothing and just shows fewer of the frames it did decode. Nor is a frame
 * dropped once nothing has been shown for [STARVED_MS]: a slow picture beats a frozen one, and the
 * fps verdict, not the drop rule, is what decides to give up.
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

    fun action(nowMs: Long, dueMs: Long, frameDurationMs: Long, sinceLastPublishMs: Long, nextReady: Boolean): Action = when {
        dueMs > nowMs -> Action.WAIT
        nextReady && nowMs - dueMs > 2 * wallMs(frameDurationMs) && sinceLastPublishMs < STARVED_MS -> Action.DROP
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
        // The timeline starts when the first frame is in hand, not when the loop does: opening the
        // file and warming the decoder up are not part of the clip, and counting them made its
        // opening frames late (and dropped) from the start.
        var started = false
        var loopStart = 0L
        var lastPublishAt = 0L
        var loopEndPts = 0L
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
                if (!started) {
                    started = true
                    loopStart = now - scheduler.wallMs(frame.ptsMs)
                    lastPublishAt = now
                    scheduler.begin(now)
                }
                var due = loopStart + scheduler.wallMs(frame.ptsMs)
                if (now - due > MotionScheduler.RESYNC_MS) {
                    loopStart += now - due
                    due = now
                }
                when (scheduler.action(now, due, frame.durationMs, now - lastPublishAt, source.nextReady)) {
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
 * Preview-sized BGRA frames -> Skia bitmaps, through a fixed ring of [RING_SIZE] reused lap after
 * lap. A fresh Skia bitmap per frame (~0.9 MB native each, ~27 MB/s at 30 fps) is freed only after
 * a Java GC, so native memory piled up and was released in one hitching burst. Reinstalling pixels
 * into a ring slot releases the old pixels by reference count instead, at once; Compose snapshots a
 * bitmap into a Skia image on every draw, so a slot is safe to refill once no frame in flight is
 * still that slot. Not thread-safe — the engine thread owns it.
 */
internal class FrameBitmaps(private val width: Int, private val height: Int) {
    private val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
    private val ring = Array(RING_SIZE) { Bitmap() }

    // Wrapped on first fill: Compose reads the bitmap's opacity once, when it wraps it.
    private val images = arrayOfNulls<ImageBitmap>(RING_SIZE)
    private var next = 0

    /** The next ring slot, holding [bgra] ([width] x [height], 4 bytes a pixel). */
    fun fill(bgra: ByteArray): ImageBitmap {
        val slot = next
        next = (next + 1) % RING_SIZE
        val bitmap = ring[slot]
        // installPixels copies into fresh native memory, so [bgra] is free for the next frame at once
        // and a Skia image still drawing this slot's previous pixels keeps them; immutable, the
        // bitmap's pixels are shared with (not copied into) each Skia image drawn from it.
        check(bitmap.installPixels(info, bgra, width * 4)) { "Skia refused a ${width}x$height frame" }
        bitmap.setImmutable()
        return images[slot] ?: bitmap.asComposeImageBitmap().also { images[slot] = it }
    }

    companion object {
        /** Every frame that can be in flight — queued in the mailbox, on screen, being filled — plus one spare. */
        const val RING_SIZE = FrameMailbox.CAPACITY + 3
    }
}

/**
 * FFmpeg behind [MotionFrameSource], decoding ahead on its own thread: while the engine paces frame
 * N, frames N+1.. decode, so a slow frame is absorbed by the ones already waiting. Each frame lands
 * in one of [SLOTS] reusable [BgraFrame]s — the bounded ring; when the engine falls behind, the
 * reader blocks on it. Looping, the reader restarts the clip the moment it reaches the end, so the
 * next pass is already decoding before the engine asks for it (no hitch at the loop point).
 */
internal class ReadAheadSource(
    private val reader: FfmpegFrameReader,
    private val loop: Boolean,
) : MotionFrameSource<ImageBitmap> {
    override val fps: Float = reader.fps

    private val bitmaps = FrameBitmaps(reader.outWidth, reader.outHeight)
    private val free = ArrayBlockingQueue<BgraFrame>(SLOTS).apply {
        repeat(SLOTS) { add(BgraFrame(reader.outWidth, reader.outHeight)) }
    }

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
                if (!reader.next(slot)) {
                    free.put(slot)
                    ready.put(END)
                    if (!loop) return
                    reader.rewind()
                    continue
                }
                ready.put(slot)
            }
        } catch (t: Throwable) {
            // Closing interrupts the reader mid-wait; anything else is a decoder that gave up.
            if (!closed && t !is InterruptedException) runCatching { ready.put(t) }
        } finally {
            reader.close()
        }
    }

    override fun decode(): DecodedFrame<ImageBitmap>? = when (val item = ready.take()) {
        END -> null
        is Throwable -> throw IOException("the motion decoder stopped", item)
        else -> {
            val frame = item as BgraFrame
            object : DecodedFrame<ImageBitmap> {
                override val ptsMs = frame.ptsMs
                override val durationMs = frame.durationMs
                override fun render(): ImageBitmap = bitmaps.fill(frame.bytes)
                override fun recycle() {
                    free.offer(frame)
                }
            }
        }
    }

    override val nextReady: Boolean get() = ready.peek() is BgraFrame

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
internal class FrameMailbox<T : Any>(private val capacity: Int = CAPACITY) {
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

    companion object {
        const val CAPACITY = 3
    }
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
            FfmpegFrameReader.open(file, crop, OUT_WIDTH, OUT_HEIGHT)?.let { ReadAheadSource(it, loop) }
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
