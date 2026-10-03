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
import kotlin.math.roundToInt
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
        assertEquals(MotionScheduler.Action.WAIT, s.action(nowMs = 100, dueMs = 120, frameDurationMs = 33, sinceLastPublishMs = 33))
        assertEquals(MotionScheduler.Action.SHOW, s.action(nowMs = 120, dueMs = 100, frameDurationMs = 33, sinceLastPublishMs = 33))
        assertEquals(MotionScheduler.Action.DROP, s.action(nowMs = 200, dueMs = 100, frameDurationMs = 33, sinceLastPublishMs = 33))
        // ... unless nothing has been shown for a while: show something rather than freeze.
        assertEquals(MotionScheduler.Action.SHOW, s.action(nowMs = 200, dueMs = 100, frameDurationMs = 33, sinceLastPublishMs = 400))
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
        // Frame spacing is the clip's 33 ms (the very first frame is only late by its decode cost);
        // a loop pass is exactly 10 frames long.
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
        // 100 ms per frame against a 33 ms clip: most frames are dropped.
        val inner = FakeSource(clock, frames = 40, fps = 30f, decodeCostMs = 100)
        var decoded = 0
        var recycled = 0
        val source = object : MotionFrameSource<Int> by inner {
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

    // ── JCodec behind the iterator ───────────────────────────────────────────

    private fun clip(frames: Int = 6): File = File.createTempFile("motion-player", ".mp4").also {
        it.deleteOnExit()
        MotionTestMedia.writeTestMp4(it, width = 320, height = 240, frames = frames)
    }

    @Test
    fun `the frame reader yields every frame in order, then null, and rewinds`() {
        val file = clip(frames = 6)
        val reader = assertNotNull(VideoCodecs.openFrames(file))
        reader.use {
            assertEquals(320, it.width)
            assertEquals(240, it.height)
            val pts = generateSequence { it.next() }.map { f -> f.ptsMs }.toList()
            assertEquals(6, pts.size)
            assertEquals(pts.sorted(), pts, "presentation order")
            assertNull(it.next())
            it.rewind()
            assertNotNull(it.next(), "rewound to the first frame")
        }
    }

    @Test
    fun `an unreadable file opens as null`() {
        val junk = File.createTempFile("not-a-video", ".mp4").also { it.writeText("nope"); it.deleteOnExit() }
        assertNull(VideoCodecs.openFrames(junk))
        assertNull(VideoCodecs.openFrames(File("does-not-exist.mp4")))
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
    fun `rewinding seeks back to the first frame and replays the same frames`() {
        val file = clip(frames = 6)
        assertNotNull(VideoCodecs.openFrames(file)).use { reader ->
            val first = generateSequence { reader.next() }.map { it.ptsMs }.toList()
            reader.rewind()
            val second = generateSequence { reader.next() }.map { it.ptsMs }.toList()
            assertEquals(first, second)
        }
    }

    @Test
    fun `the read-ahead source loops the real decoder and releases its thread`() {
        val file = clip(frames = 6)
        val clock = FakeClock()
        val published = mutableListOf<Long>()
        val outcome = runBlocking {
            MotionEngine.run(
                open = { ReadAheadSource(assertNotNull(VideoCodecs.openFrames(file)), loop = true, crop = null, outW = 64, outH = 36) },
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

    // ── FrameConverter: the same pixels as JCodec's AWTUtil path ─────────────

    /**
     * The crop-and-scale the preview did before FrameConverter, kept as the reference it must match:
     * the ARGB pixels it handed to Skia (as opaque BGRA, so the alpha byte was ignored).
     */
    private fun legacyPixels(src: BufferedImage, crop: MotionCrop?, outW: Int, outH: Int): IntArray {
        val r = MotionFraming.visibleRegion(src.width.toFloat(), src.height.toFloat(), outW.toFloat(), outH.toFloat(), crop)
        val sx1 = (r.left * src.width).roundToInt().coerceIn(0, src.width - 1)
        val sy1 = (r.top * src.height).roundToInt().coerceIn(0, src.height - 1)
        val sx2 = ((r.left + r.width) * src.width).roundToInt().coerceIn(sx1 + 1, src.width)
        val sy2 = ((r.top + r.height) * src.height).roundToInt().coerceIn(sy1 + 1, src.height)
        val out = BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, outW, outH, sx1, sy1, sx2, sy2, null)
        g.dispose()
        return (out.raster.dataBuffer as DataBufferInt).data
    }

    private val OPAQUE = 0xFF000000.toInt()

    /** The ARGB pixels of a frame bitmap, read back through Skia. */
    private fun ImageBitmap.argb(): IntArray {
        val bytes = assertNotNull(asSkiaBitmap().readPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)))
        val out = IntArray(width * height)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out)
        return out
    }

    /** A clip whose size is not a whole number of macroblocks (so the picture is cropped), with detail in every channel. */
    private fun patternedClip(width: Int, height: Int, frames: Int): File = File.createTempFile("motion-pattern", ".mp4").also {
        it.deleteOnExit()
        val encoder = SequenceEncoder.createSequenceEncoder(it, 10)
        for (i in 0 until frames) {
            val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            for (x in 0 until width) for (y in 0 until height) {
                val r = (x * 255 / width + i * 20) and 0xFF
                val g = (y * 255 / height) and 0xFF
                val b = ((x xor y) * 7 + i * 31) and 0xFF
                img.setRGB(x, y, (r shl 16) or (g shl 8) or b)
            }
            encoder.encodeNativeFrame(AWTUtil.fromBufferedImageRGB(img))
        }
        encoder.finish()
    }

    @Test
    fun `the converter draws exactly what the AWTUtil path drew`() {
        val file = patternedClip(width = 330, height = 250, frames = 3)
        val crops = listOf(null, MotionCrop(0.1f, 0.2f, 0.5f, 0.6f), MotionCrop(0f, 0f, 0.2f, 1f))
        assertNotNull(VideoCodecs.openFrames(file)).use { reader ->
            val planes = VideoCodecs.YuvPlanes()
            for (index in 0 until 3) {
                val raw = assertNotNull(reader.next())
                raw.copyInto(planes)
                assertEquals(330, planes.width)
                assertEquals(250, planes.height)
                val reference = assertNotNull(VideoCodecs.frameAt(file, raw.ptsMs))
                for (crop in crops) {
                    val expected = legacyPixels(reference, crop, 640, 360)
                    val actual = FrameConverter(crop, 640, 360).convert(planes).argb()
                    val diffs = expected.indices.filter { (expected[it] or OPAQUE) != actual[it] }
                    assertTrue(
                        diffs.isEmpty(),
                        "frame $index, crop $crop: ${diffs.size} pixels differ, first " +
                            diffs.take(5).joinToString { "#$it ${Integer.toHexString(expected[it])} vs ${Integer.toHexString(actual[it])}" },
                    )
                }
            }
        }
    }

    @Test
    fun `one converter reused across frames matches a fresh one`() {
        val file = patternedClip(width = 330, height = 250, frames = 3)
        val reused = FrameConverter(null, 160, 90)
        assertNotNull(VideoCodecs.openFrames(file)).use { reader ->
            val planes = VideoCodecs.YuvPlanes()
            repeat(3) {
                assertNotNull(reader.next()).copyInto(planes)
                assertContentEquals(
                    FrameConverter(null, 160, 90).convert(planes).argb(),
                    reused.convert(planes).argb(),
                )
            }
        }
    }
}
