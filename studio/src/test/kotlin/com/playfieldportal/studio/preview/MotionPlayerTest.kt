package com.playfieldportal.studio.preview

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import com.playfieldportal.studio.MotionTestMedia
import com.playfieldportal.studio.io.VideoCodecs
import com.playfieldportal.themekit.MotionCrop
import com.playfieldportal.themekit.PfpThemeManifest
import java.awt.EventQueue
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jcodec.api.SequenceEncoder
import org.jcodec.scale.AWTUtil
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/** A frame source with a fixed script and a decode cost charged to a shared fake clock. */
private class FakeClock : MotionClock {
    var now = 0L
    override fun nowMs() = now
    override suspend fun sleep(ms: Long) {
        now += ms
    }
}

private class FakeSource(
    private val clock: FakeClock,
    private val frames: Int,
    override val fps: Float,
    private val decodeCostMs: Long,
    private val threads: MutableSet<String> = mutableSetOf(),
) : MotionFrameSource<Int> {
    private var index = 0
    var rewinds = 0
    var closed = false
    val decodeThreads: Set<String> get() = threads
    private val frameMs = (1000f / fps).toLong()

    override fun decode(): DecodedFrame<Int>? {
        threads += Thread.currentThread().name
        if (index >= frames) return null
        clock.now += decodeCostMs
        val i = index++
        return object : DecodedFrame<Int> {
            override val ptsMs = i * frameMs
            override val durationMs = frameMs
            override fun render() = i
        }
    }

    override fun rewind() {
        rewinds++
        index = 0
    }

    override fun close() {
        closed = true
    }
}

class MotionPlayerTest {

    private val animated = WaveMotion.paramsFor(PfpThemeManifest.WAVE_ANIMATED)
    private val reduced = WaveMotion.paramsFor(PfpThemeManifest.WAVE_REDUCED)

    // ── Scheduler: pure decisions ────────────────────────────────────────────

    @Test
    fun `frame times scale with speed`() {
        assertEquals(1000L, MotionScheduler(1f, 30f).wallMs(1000L))
        assertEquals(2000L, MotionScheduler(0.5f, 30f).wallMs(1000L))
    }

    @Test
    fun `required fps is the floor, or most of a slow clip's own rate`() {
        assertEquals(12f, MotionScheduler(1f, 30f).requiredFps, 1e-4f)
        assertEquals(8f, MotionScheduler(1f, 10f).requiredFps, 1e-4f)
        // Reduced plays at half rate, so it is asked for half as much.
        assertEquals(12f, MotionScheduler(0.5f, 30f).requiredFps, 1e-4f)
        assertEquals(6f, MotionScheduler(0.5f, 15f).requiredFps, 1e-4f)
    }

    @Test
    fun `an early frame waits, a slightly late one shows, a very late one is dropped`() {
        val s = MotionScheduler(1f, 30f)
        assertEquals(MotionScheduler.Action.WAIT, s.action(nowMs = 100, dueMs = 120, frameDurationMs = 33, sinceLastPublishMs = 33, nextReady = true))
        assertEquals(MotionScheduler.Action.SHOW, s.action(nowMs = 120, dueMs = 100, frameDurationMs = 33, sinceLastPublishMs = 33, nextReady = true))
        assertEquals(MotionScheduler.Action.DROP, s.action(nowMs = 200, dueMs = 100, frameDurationMs = 33, sinceLastPublishMs = 33, nextReady = true))
        // ... unless nothing has been shown for a while: show something rather than freeze.
        assertEquals(MotionScheduler.Action.SHOW, s.action(nowMs = 200, dueMs = 100, frameDurationMs = 33, sinceLastPublishMs = 400, nextReady = true))
    }

    @Test
    fun `a very late frame is shown when no newer frame is decoded yet`() {
        // Dropping saves only the conversion; with the decoder behind, there is nothing to skip to.
        val s = MotionScheduler(1f, 30f)
        assertEquals(MotionScheduler.Action.SHOW, s.action(nowMs = 200, dueMs = 100, frameDurationMs = 33, sinceLastPublishMs = 33, nextReady = false))
    }

    @Test
    fun `the verdict waits out the warm-up then judges a full window`() {
        val s = MotionScheduler(1f, 30f)
        s.begin(0)
        assertEquals(MotionScheduler.Verdict.OK, s.verdict(1_000), "inside the warm-up nothing is judged")
        assertEquals(MotionScheduler.Verdict.OK, s.verdict(2_000), "the window has not elapsed")
        repeat(10) { s.recordPublished() } // 10 frames over 2 s of window = 5 fps < 12
        assertEquals(MotionScheduler.Verdict.FALL_BACK, s.verdict(3_500))
    }

    @Test
    fun `a window that delivers enough passes and the next window starts fresh`() {
        val s = MotionScheduler(1f, 30f)
        s.begin(0)
        repeat(60) { s.recordPublished() } // 30 fps over the 2 s window
        assertEquals(MotionScheduler.Verdict.OK, s.verdict(3_500))
        assertEquals(MotionScheduler.Verdict.OK, s.verdict(4_000), "a new window has not elapsed yet")
    }

    @Test
    fun `frozen styles and a covered frame request no frames`() {
        assertTrue(MotionScheduler.requestsFrames(animated))
        assertTrue(MotionScheduler.requestsFrames(reduced))
        assertFalse(MotionScheduler.requestsFrames(WaveMotion.paramsFor(PfpThemeManifest.WAVE_STATIC)))
        assertFalse(MotionScheduler.requestsFrames(WaveMotion.paramsFor(PfpThemeManifest.WAVE_REDUCED_STATIC)))
        assertFalse(MotionScheduler.requestsFrames(animated, covered = true))
    }

    // ── Engine: loop timing, speed, give-up, with a fake decoder ─────────────

    private fun <T> play(
        source: MotionFrameSource<T>,
        clock: FakeClock,
        speed: Float,
        loop: Boolean = true,
        stopAfter: Int = Int.MAX_VALUE,
        published: MutableList<Pair<Long, T>>,
    ): MotionOutcome = runBlocking {
        MotionEngine.run(
            open = { source },
            scheduler = { fps -> MotionScheduler(speed, fps) },
            clock = clock,
            decodeContext = kotlin.coroutines.EmptyCoroutineContext,
            loop = loop,
            shouldStop = { published.size >= stopAfter },
            publish = { _, frame -> published += clock.now to frame },
        )
    }

    @Test
    fun `a decoder that keeps up plays every frame on time and loops`() {
        val clock = FakeClock()
        val source = FakeSource(clock, frames = 10, fps = 30f, decodeCostMs = 5)
        val published = mutableListOf<Pair<Long, Int>>()
        val outcome = play(source, clock, speed = 1f, stopAfter = 25, published = published)

        assertEquals(MotionOutcome.ENDED, outcome, "stopped by the caller, never by a fallback")
        assertEquals(List(25) { it % 10 }, published.map { it.second }, "frames 0..9 then the loop restarts at 0")
        assertEquals(2, source.rewinds)
        assertTrue(source.closed, "the source is released when the loop ends")
        // Frame spacing is the clip's 33 ms; a loop pass is exactly 10 frames long.
        assertEquals(33L, published[2].first - published[1].first)
        assertEquals(330L, published[11].first - published[1].first)
    }

    @Test
    fun `reduced plays at half speed`() {
        val clock = FakeClock()
        val published = mutableListOf<Pair<Long, Int>>()
        play(FakeSource(clock, frames = 10, fps = 30f, decodeCostMs = 2), clock, speed = 0.5f, stopAfter = 5, published = published)
        assertEquals(66L, published[2].first - published[1].first)
    }

    @Test
    fun `a decoder that cannot keep up falls back to the poster`() {
        val clock = FakeClock()
        // 200 ms per frame is 5 fps against a 12 fps requirement.
        val source = FakeSource(clock, frames = 1000, fps = 30f, decodeCostMs = 200)
        val published = mutableListOf<Pair<Long, Int>>()
        val outcome = play(source, clock, speed = 1f, published = published)
        assertEquals(MotionOutcome.FELL_BACK, outcome)
        assertTrue(published.isNotEmpty(), "it showed what it could before giving up")
        assertTrue(source.closed)
    }

    @Test
    fun `a clip that is slow only at first is not judged during warm-up`() {
        val clock = FakeClock()
        // One 1.2 s hiccup (class loading) followed by a healthy stream.
        var first = true
        val inner = FakeSource(clock, frames = 1000, fps = 30f, decodeCostMs = 3)
        val source = object : MotionFrameSource<Int> by inner {
            override fun decode(): DecodedFrame<Int>? {
                if (first) {
                    first = false
                    clock.now += 1_200
                }
                return inner.decode()
            }
        }
        val published = mutableListOf<Pair<Long, Int>>()
        val outcome = play(source, clock, speed = 1f, stopAfter = 200, published = published)
        assertEquals(MotionOutcome.ENDED, outcome)
    }

    @Test
    fun `a decoder slower than the clip but above the floor shows every frame it decodes`() {
        val clock = FakeClock()
        // 50 ms per frame is 20 fps against a 30 fps clip: behind, but well above the 12 fps floor.
        val source = FakeSource(clock, frames = 100, fps = 30f, decodeCostMs = 50)
        val published = mutableListOf<Pair<Long, Int>>()
        val outcome = play(source, clock, speed = 1f, loop = false, published = published)
        assertEquals(MotionOutcome.ENDED, outcome, "20 fps keeps up with the floor, so it never falls back")
        assertEquals(List(100) { it }, published.map { it.second }, "no decoded frame is thrown away")
    }

    @Test
    fun `the clock starts at the first frame, so a slow start is not played as lateness`() {
        val clock = FakeClock()
        // Opening the file and warming the decoder up costs 600 ms before frame 0 arrives.
        var first = true
        val inner = FakeSource(clock, frames = 30, fps = 30f, decodeCostMs = 3)
        val source = object : MotionFrameSource<Int> by inner {
            override fun decode(): DecodedFrame<Int>? {
                if (first) {
                    first = false
                    clock.now += 600
                }
                return inner.decode()
            }
        }
        val published = mutableListOf<Pair<Long, Int>>()
        val outcome = play(source, clock, speed = 1f, loop = false, published = published)
        assertEquals(MotionOutcome.ENDED, outcome)
        assertEquals(List(30) { it }, published.map { it.second }, "the opening frames are all shown")
        assertEquals(33L, published[1].first - published[0].first, "frame 1 follows frame 0 at the clip's spacing")
        assertEquals(33L, published[2].first - published[1].first)
    }

    @Test
    fun `a one-shot clip ends at the end of the stream`() {
        val clock = FakeClock()
        val published = mutableListOf<Pair<Long, Int>>()
        val outcome = play(FakeSource(clock, 6, 30f, 2), clock, speed = 1f, loop = false, published = published)
        assertEquals(MotionOutcome.ENDED, outcome)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), published.map { it.second })
    }

    @Test
    fun `a source that cannot be opened fails without throwing`() {
        val outcome = runBlocking {
            MotionEngine.run<Int>(
                open = { null },
                scheduler = { MotionScheduler(1f, it) },
                clock = FakeClock(),
                decodeContext = kotlin.coroutines.EmptyCoroutineContext,
                loop = true,
                publish = { _, _ -> },
            )
        }
        assertEquals(MotionOutcome.FAILED, outcome)
    }

    @Test
    fun `a decoder that throws mid-stream fails the clip, not the app`() {
        val clock = FakeClock()
        val source = object : MotionFrameSource<Int> by FakeSource(clock, 3, 30f, 1) {
            override fun decode(): DecodedFrame<Int>? = throw java.io.IOException("corrupt")
        }
        val outcome = play(source, clock, speed = 1f, published = mutableListOf())
        assertEquals(MotionOutcome.FAILED, outcome)
    }

    @Test
    fun `a lead hands each frame over that much before it is due, with its due time`() {
        val clock = FakeClock()
        val source = FakeSource(clock, frames = 10, fps = 30f, decodeCostMs = 2)
        val handed = mutableListOf<Pair<Long, Long>>() // clock time handed over -> due time
        runBlocking {
            MotionEngine.run(
                open = { source },
                scheduler = { fps -> MotionScheduler(1f, fps) },
                clock = clock,
                decodeContext = kotlin.coroutines.EmptyCoroutineContext,
                loop = false,
                leadMs = 10,
                publish = { due, _ -> handed += clock.now to due },
            )
        }
        assertEquals(10, handed.size)
        for ((at, due) in handed.drop(1)) assertEquals(due - 10, at)
        assertEquals(33L, handed[2].second - handed[1].second, "due times keep the clip's spacing")
    }

    @Test
    fun `every decoded frame is recycled, shown or dropped`() {
        val clock = FakeClock()
        // 100 ms per frame against a 33 ms clip, with the next frame always waiting: most are dropped.
        val inner = FakeSource(clock, frames = 40, fps = 30f, decodeCostMs = 100)
        var decoded = 0
        var recycled = 0
        val source = object : MotionFrameSource<Int> by inner {
            override val nextReady = true

            override fun decode(): DecodedFrame<Int>? {
                val frame = inner.decode() ?: return null
                decoded++
                return object : DecodedFrame<Int> by frame {
                    override fun recycle() {
                        recycled++
                    }
                }
            }
        }
        val published = mutableListOf<Pair<Long, Int>>()
        play(source, clock, speed = 1f, loop = false, published = published)
        assertTrue(published.size < decoded, "frames were dropped")
        assertEquals(decoded, recycled)
    }

    // ── Mailbox: frames shown on the vsync they are due ──────────────────────

    @Test
    fun `the mailbox gives the newest due frame and keeps the ones still ahead`() {
        val box = FrameMailbox<String>()
        box.offer(100, "a")
        box.offer(133, "b")
        box.offer(166, "c")
        assertNull(box.takeDue(99), "nothing is due yet")
        assertEquals("b", box.takeDue(140), "a was superseded by b")
        assertFalse(box.isEmpty())
        assertEquals("c", box.takeDue(200))
        assertTrue(box.isEmpty())
    }

    @Test
    fun `a full mailbox drops its oldest frame`() {
        val box = FrameMailbox<String>(capacity = 2)
        box.offer(1, "a")
        box.offer(2, "b")
        box.offer(3, "c")
        assertEquals("c", box.takeDue(10))
        assertTrue(box.isEmpty())
    }

    @Test
    fun `decoding runs on the decode thread and never on the UI thread`() {
        val clock = FakeClock()
        val source = FakeSource(clock, frames = 4, fps = 30f, decodeCostMs = 1)
        val caller = Thread.currentThread().name
        val outcome = runBlocking {
            MotionEngine.run(
                open = { source },
                scheduler = { MotionScheduler(1f, it) },
                clock = clock,
                decodeContext = MotionPlayer.decodeDispatcher,
                loop = false,
                publish = { _, _ -> },
            )
        }
        assertEquals(MotionOutcome.ENDED, outcome)
        assertTrue(source.decodeThreads.isNotEmpty())
        for (name in source.decodeThreads) {
            // The coroutine debug agent may append " @coroutine#n" to the thread name.
            assertTrue(name.startsWith(MotionPlayer.DECODE_THREAD), name)
            assertTrue(name != caller)
        }
        assertFalse(EventQueue.isDispatchThread())
    }

    // ── Framing: the launcher's motionTransform as a source rectangle ────────

    @Test
    fun `without a crop the visible region is the legacy centre crop`() {
        // 16:9 into 16:9: everything.
        val whole = MotionFraming.visibleRegion(1920f, 1080f, 640f, 360f, null)
        assertEquals(NormRect(0f, 0f, 1f, 1f), whole.rounded())
        // 4:3 into 16:9: full width, the middle 75% of the height.
        val wide = MotionFraming.visibleRegion(640f, 480f, 640f, 360f, null)
        assertEquals(NormRect(0f, 0.125f, 1f, 0.75f), wide.rounded())
        // 9:16 into 16:9: full width, a thin centred band.
        val tall = MotionFraming.visibleRegion(360f, 640f, 640f, 360f, null)
        assertEquals(0f, tall.left, 1e-5f)
        assertEquals(1f, tall.width, 1e-5f)
        assertEquals(0.5f - tall.height / 2f, tall.top, 1e-5f)
    }

    @Test
    fun `a crop shows exactly its region when the aspects agree`() {
        val left = MotionFraming.visibleRegion(1000f, 1000f, 500f, 500f, MotionCrop(0f, 0f, 0.5f, 0.5f))
        assertEquals(NormRect(0f, 0f, 0.5f, 0.5f), left.rounded())
        val bottomRight = MotionFraming.visibleRegion(1000f, 1000f, 500f, 500f, MotionCrop(0.5f, 0.5f, 0.5f, 0.5f))
        assertEquals(NormRect(0.5f, 0.5f, 0.5f, 0.5f), bottomRight.rounded())
        val centre = MotionFraming.visibleRegion(1000f, 1000f, 500f, 500f, MotionCrop(0.25f, 0.25f, 0.5f, 0.5f))
        assertEquals(NormRect(0.25f, 0.25f, 0.5f, 0.5f), centre.rounded())
    }

    @Test
    fun `an overflowing crop is clamped so the frame still covers the view`() {
        // The region is a thin vertical strip at the far left; covering the 16:9 view needs more width
        // than the strip, so the view is centred on it and clamped to the frame's left edge.
        val r = MotionFraming.visibleRegion(1920f, 1080f, 640f, 360f, MotionCrop(0f, 0f, 0.2f, 1f))
        assertTrue(r.left >= -1e-6f && r.left + r.width <= 1f + 1e-6f)
        assertTrue(r.top >= -1e-6f && r.top + r.height <= 1f + 1e-6f)
        assertEquals(0f, r.left, 1e-5f)
    }

    private fun NormRect.rounded() = NormRect(
        Math.round(left * 1000f) / 1000f, Math.round(top * 1000f) / 1000f,
        Math.round(width * 1000f) / 1000f, Math.round(height * 1000f) / 1000f,
    )

    // ── FFmpeg behind the iterator ───────────────────────────────────────────

    private fun clip(frames: Int = 6): File = File.createTempFile("motion-player", ".mp4").also {
        it.deleteOnExit()
        MotionTestMedia.writeTestMp4(it, width = 320, height = 240, frames = frames)
    }

    private fun FfmpegFrameReader.ptsUntilEnd(into: BgraFrame): List<Long> =
        generateSequence { if (next(into)) into.ptsMs else null }.toList()

    @Test
    fun `the frame reader yields every frame in order at the preview size, then stops, and rewinds`() {
        val file = clip(frames = 6)
        assertNotNull(FfmpegFrameReader.open(file, crop = null, outW = 64, outH = 36)).use { reader ->
            assertEquals(10f, reader.fps, 0.01f)
            val frame = BgraFrame(64, 36)
            val pts = reader.ptsUntilEnd(frame)
            assertEquals(listOf(0L, 100L, 200L, 300L, 400L, 500L), pts)
            assertEquals(100L, frame.durationMs)
            assertFalse(reader.next(frame), "still at the end")
            reader.rewind()
            assertEquals(pts, reader.ptsUntilEnd(frame), "rewound to the first frame, the same frames again")
        }
    }

    @Test
    fun `an unreadable file opens as null`() {
        val junk = File.createTempFile("not-a-video", ".mp4").also { it.writeText("nope"); it.deleteOnExit() }
        assertNull(FfmpegFrameReader.open(junk, null, 64, 36))
        assertNull(FfmpegFrameReader.open(File("does-not-exist.mp4"), null, 64, 36))
    }

    @Test
    fun `the real player decodes a clip into preview-sized frames`() {
        val file = clip(frames = 6)
        val frames = mutableListOf<androidx.compose.ui.graphics.ImageBitmap>()
        val outcome = runBlocking {
            MotionPlayer.play(file, crop = null, speed = 1f, loop = false) { _, frame -> frames += frame }
        }
        assertEquals(MotionOutcome.ENDED, outcome)
        assertEquals(6, frames.size)
        assertEquals(MotionPlayer.OUT_WIDTH, frames.first().width)
        assertEquals(MotionPlayer.OUT_HEIGHT, frames.first().height)
    }

    @Test
    fun `the real player reports an unreadable file as failed`() {
        val junk = File.createTempFile("not-a-video", ".mp4").also { it.writeText("nope"); it.deleteOnExit() }
        val outcome = runBlocking { MotionPlayer.play(junk, null, 1f, loop = true) { _, _ -> } }
        assertEquals(MotionOutcome.FAILED, outcome)
    }

    @Test
    fun `the read-ahead source loops the real decoder and releases its thread`() {
        val file = clip(frames = 6)
        val clock = FakeClock()
        val published = mutableListOf<Long>()
        val outcome = runBlocking {
            MotionEngine.run(
                open = { ReadAheadSource(assertNotNull(FfmpegFrameReader.open(file, null, 64, 36)), loop = true) },
                scheduler = { fps -> MotionScheduler(1f, fps) },
                clock = clock,
                decodeContext = MotionPlayer.decodeDispatcher,
                loop = true,
                shouldStop = { published.size >= 15 },
                publish = { due, frame ->
                    assertEquals(64, frame.width)
                    published += due
                },
            )
        }
        assertEquals(MotionOutcome.ENDED, outcome)
        assertEquals(15, published.size, "two and a half passes of a six-frame clip")
        assertEquals(published.sorted(), published, "the timeline runs on across the loop points")
        val readers = Thread.getAllStackTraces().keys.filter { it.name == "${MotionPlayer.DECODE_THREAD}-read" && it.isAlive }
        assertTrue(readers.isEmpty(), "closing the source stops its reader")
    }

    @Test
    fun `the read-ahead source says when a newer frame is already decoded`() {
        val file = clip(frames = 6)
        val source = ReadAheadSource(assertNotNull(FfmpegFrameReader.open(file, null, 64, 36)), loop = false)
        source.use {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!it.nextReady && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(it.nextReady, "the reader decodes ahead of the engine")
            repeat(6) { _ -> assertNotNull(it.decode()).recycle() }
            assertFalse(it.nextReady, "the end of the stream is not a frame to skip to")
        }
    }

    // ── Framing: FFmpeg shows what the JCodec path showed ────────────────────

    /**
     * The crop-and-scale the preview did with JCodec, kept as the reference: frame [atMs] decoded by
     * JCodec and cropped / scaled bilinearly by Java2D, as RGB ints.
     */
    private fun referencePixels(file: File, atMs: Long, crop: MotionCrop?, outW: Int, outH: Int): IntArray {
        val src = assertNotNull(VideoCodecs.frameAt(file, atMs))
        val r = MotionFraming.sourceRect(src.width, src.height, outW, outH, crop)
        val out = BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, outW, outH, r.left, r.top, r.right, r.bottom, null)
        g.dispose()
        return (out.raster.dataBuffer as DataBufferInt).data
    }

    /** A BGRA frame as RGB ints. */
    private fun BgraFrame.rgb(): IntArray = IntArray(width * height) { i ->
        (bytes[i * 4 + 2].toInt() and 0xFF shl 16) or (bytes[i * 4 + 1].toInt() and 0xFF shl 8) or (bytes[i * 4].toInt() and 0xFF)
    }

    private fun meanChannelDiff(a: IntArray, b: IntArray): Double {
        var sum = 0L
        for (i in a.indices) for (shift in intArrayOf(0, 8, 16)) {
            sum += abs((a[i] shr shift and 0xFF) - (b[i] shr shift and 0xFF))
        }
        return sum.toDouble() / (a.size * 3)
    }

    /**
     * A clip with a smooth gradient in every channel (so two different scalers agree on it), its size
     * not a whole number of macroblocks (so the decoded picture is cropped).
     */
    private fun gradientClip(width: Int, height: Int, frames: Int): File = File.createTempFile("motion-pattern", ".mp4").also {
        it.deleteOnExit()
        val encoder = SequenceEncoder.createSequenceEncoder(it, 10)
        for (i in 0 until frames) {
            val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            for (x in 0 until width) for (y in 0 until height) {
                val r = x * 180 / width + i * 20
                val g = y * 200 / height
                val b = (x + y) * 200 / (width + height)
                img.setRGB(x, y, (r shl 16) or (g shl 8) or b)
            }
            encoder.encodeNativeFrame(AWTUtil.fromBufferedImageRGB(img))
        }
        encoder.finish()
    }

    @Test
    fun `the reader frames the clip as the launcher does, crop and all`() {
        val file = gradientClip(width = 330, height = 250, frames = 3)
        val crops = listOf(null, MotionCrop(0.1f, 0.2f, 0.5f, 0.6f), MotionCrop(0f, 0f, 0.2f, 1f), MotionCrop(0.5f, 0.5f, 0.5f, 0.5f))
        for (crop in crops) {
            assertNotNull(FfmpegFrameReader.open(file, crop, 160, 90)).use { reader ->
                val frame = BgraFrame(160, 90)
                for (index in 0 until 3) {
                    assertTrue(reader.next(frame))
                    val diff = meanChannelDiff(referencePixels(file, frame.ptsMs, crop, 160, 90), frame.rgb())
                    assertTrue(diff < 4.0, "frame $index, crop $crop: %.2f per channel off the JCodec framing".format(diff))
                }
            }
        }
    }

    @Test
    fun `frames come out opaque`() {
        val file = clip(frames = 1)
        assertNotNull(FfmpegFrameReader.open(file, null, 64, 36)).use { reader ->
            val frame = BgraFrame(64, 36)
            assertTrue(reader.next(frame))
            assertTrue((0 until 64 * 36).all { frame.bytes[it * 4 + 3] == 0xFF.toByte() })
        }
    }

    // ── FrameBitmaps: a ring of Skia bitmaps, never one per frame ────────────

    /** The ARGB pixels of a frame bitmap, read back through Skia. */
    private fun ImageBitmap.argb(): IntArray {
        val bytes = assertNotNull(asSkiaBitmap().readPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)))
        val out = IntArray(width * height)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out)
        return out
    }

    private fun solid(width: Int, height: Int, argb: Int): ByteArray =
        ByteBuffer.allocate(width * height * 4).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(width * height) { putInt(argb) } }.array()

    @Test
    fun `a filled bitmap holds exactly the frame's pixels`() {
        val bitmaps = FrameBitmaps(4, 3)
        val bytes = ByteArray(4 * 3 * 4) { (it * 7).toByte() }.also { b -> for (i in 0 until 12) b[i * 4 + 3] = 0xFF.toByte() }
        val expected = IntArray(12).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(it) }
        assertContentEquals(expected, bitmaps.fill(bytes).argb())
    }

    @Test
    fun `the bitmaps cycle a fixed ring`() {
        val bitmaps = FrameBitmaps(16, 9)
        val bytes = solid(16, 9, 0xFF336699.toInt())
        val frames = List(FrameBitmaps.RING_SIZE + 1) { bitmaps.fill(bytes) }
        assertEquals(FrameBitmaps.RING_SIZE, frames.take(FrameBitmaps.RING_SIZE).toSet().size, "a full lap is all distinct bitmaps")
        assertTrue(frames.last() === frames.first(), "the next lap reuses the first bitmap")
    }

    @Test
    fun `a frame keeps its pixels until the ring comes back round`() {
        val bitmaps = FrameBitmaps(16, 9)
        val held = bitmaps.fill(solid(16, 9, 0xFF112233.toInt()))
        val pixels = held.argb()
        repeat(FrameBitmaps.RING_SIZE - 1) { bitmaps.fill(solid(16, 9, 0xFFAABBCC.toInt())) }
        assertContentEquals(pixels, held.argb(), "frames still in flight are never overwritten")
    }

    @Test
    fun `the ring outlasts every frame that can be in flight`() {
        // Queued in the mailbox, on screen, and the one being filled.
        assertTrue(FrameBitmaps.RING_SIZE >= FrameMailbox.CAPACITY + 2)
    }
}
