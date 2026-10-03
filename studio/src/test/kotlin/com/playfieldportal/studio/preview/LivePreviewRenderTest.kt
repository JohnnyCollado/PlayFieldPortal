package com.playfieldportal.studio.preview

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.playfieldportal.studio.IconGifTestMedia
import com.playfieldportal.studio.MotionTestMedia
import com.playfieldportal.studio.StudioState
import com.playfieldportal.themekit.PfpThemeManifest
import java.awt.EventQueue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * TS-33 render smoke tests: the live frame composes and draws on the AWT thread, the wave moves in
 * the animated styles and holds still in the frozen ones, the exported frame never moves, and the
 * boot sequences play out and report their end exactly once.
 */
@OptIn(ExperimentalComposeUiApi::class)
class LivePreviewRenderTest {

    private val frameNanos = 16_666_667L

    /** Renders [content]'s frames and returns the PNG of each sample point (frame index -> bytes). */
    private fun renderSamples(
        spec: PreviewLiveSpec?,
        model: XmbPreviewModel,
        sampleAt: Set<Int>,
        frames: Int,
        stepNanos: Long = frameNanos,
        realTimePerFrameMs: Long = 0,
        onBootFinished: () -> Unit = {},
    ): Map<Int, ByteArray> {
        val out = mutableMapOf<Int, ByteArray>()
        EventQueue.invokeAndWait {
            ImageComposeScene(width = 640, height = 360, density = Density(640f / PreviewGeometry.BASE_WIDTH)).use { scene ->
                scene.setContent { XmbFrame(model, live = spec, onBootFinished = onBootFinished) }
                var t = 0L
                for (i in 0 until frames) {
                    val image = scene.render(t)
                    if (i in sampleAt) out[i] = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
                    t += stepNanos
                    if (realTimePerFrameMs > 0) {
                        Snapshot.sendApplyNotifications()
                        Thread.sleep(realTimePerFrameMs)
                    }
                }
            }
        }
        return out
    }

    private fun model(style: String) = StudioState(waveStyle = style).toPreviewModel()

    @Test
    fun `an animated wave moves between frames`() {
        val shots = renderSamples(PreviewLiveSpec(), model(PfpThemeManifest.WAVE_ANIMATED), setOf(5, 120), frames = 121, stepNanos = 50_000_000L)
        assertFalse(shots[5]!!.contentEquals(shots[120]!!), "the crest drifts over six seconds")
    }

    @Test
    fun `a reduced wave moves too`() {
        val shots = renderSamples(PreviewLiveSpec(), model(PfpThemeManifest.WAVE_REDUCED), setOf(5, 120), frames = 121, stepNanos = 50_000_000L)
        assertFalse(shots[5]!!.contentEquals(shots[120]!!))
    }

    @Test
    fun `frozen styles hold still however long they run`() {
        for (style in listOf(PfpThemeManifest.WAVE_STATIC, PfpThemeManifest.WAVE_REDUCED_STATIC)) {
            val shots = renderSamples(PreviewLiveSpec(), model(style), setOf(5, 120), frames = 121, stepNanos = 50_000_000L)
            assertContentEquals(shots[5], shots[120], "$style is posed at t=2 and never changes")
        }
    }

    @Test
    fun `the exported frame is the same static pose whatever the style`() {
        // No live spec: even an animated style renders its frozen pose, identically at any time.
        val shots = renderSamples(null, model(PfpThemeManifest.WAVE_ANIMATED), setOf(0, 60), frames = 61, stepNanos = 50_000_000L)
        assertContentEquals(shots[0], shots[60])
        // ... and it matches the frozen style's pose, since both draw at STATIC_TIME.
        val frozen = renderSamples(null, model(PfpThemeManifest.WAVE_STATIC), setOf(0), frames = 1)
        assertContentEquals(frozen[0], shots[0])
    }

    @Test
    fun `a focused gif icon composes alongside the static ones`() {
        val gif = IconGifTestMedia.animatedGif(frames = 3, delayCs = 10)
        val still = ImageBitmap(32, 32)
        val state = StudioState(
            iconOverrides = mapOf("catbar_video" to gif, "item_memcard_video" to gif),
            iconExtensions = mapOf("catbar_video" to "gif", "item_memcard_video" to "gif"),
            iconBitmaps = mapOf("catbar_video" to still, "item_memcard_video" to still),
        )
        val spec = PreviewLiveSpec(iconGifs = GifFrames.animatedIcons(state))
        assertEquals(setOf("catbar_video", "item_memcard_video"), spec.iconGifs.keys)
        // Real time between frames so the off-thread decode lands and the clock advances through the loop.
        renderSamples(spec, state.toPreviewModel(), setOf(30), frames = 40, stepNanos = 100_000_000L, realTimePerFrameMs = 30)
    }

    @Test
    fun `the built-in boot plays out and reports its end exactly once`() {
        var finished = 0
        val spec = PreviewLiveSpec(boot = BootPlayback.play(BootKind.BOOT, null))
        val shots = renderSamples(spec, model(PfpThemeManifest.WAVE_ANIMATED), setOf(0, 30), frames = 50, stepNanos = 100_000_000L) { finished++ }
        assertEquals(1, finished)
        assertFalse(shots[0]!!.contentEquals(shots[30]!!), "the logo fades in")
    }

    @Test
    fun `the built-in gameboot plays out in five seconds and reports its end exactly once`() {
        var finished = 0
        val spec = PreviewLiveSpec(boot = BootPlayback.play(BootKind.GAMEBOOT, null))
        val shots = renderSamples(spec, model(PfpThemeManifest.WAVE_ANIMATED), setOf(10, 31, 45), frames = 56, stepNanos = 100_000_000L) { finished++ }
        assertEquals(1, finished, "5.0 s at 100 ms per frame ends by frame 51")
        assertFalse(shots[10]!!.contentEquals(shots[31]!!), "black at 1 s, the lit field at 3.1 s")
        assertFalse(shots[31]!!.contentEquals(shots[45]!!))
    }

    @Test
    fun `a frozen theme still plays gameboot, without the blooms`() {
        var finished = 0
        val spec = PreviewLiveSpec(boot = BootPlayback.play(BootKind.GAMEBOOT, null))
        renderSamples(spec, model(PfpThemeManifest.WAVE_REDUCED_STATIC), setOf(31), frames = 56, stepNanos = 100_000_000L) { finished++ }
        assertEquals(1, finished)
    }

    @Test
    fun `a custom clip plays through the real decoder and ends`() {
        val clip = File.createTempFile("boot-preview", ".mp4").also {
            it.deleteOnExit()
            MotionTestMedia.writeTestMp4(it, width = 320, height = 240, frames = 6)
        }
        val finished = java.util.concurrent.atomic.AtomicInteger()
        val spec = PreviewLiveSpec(boot = BootPlayback.play(BootKind.BOOT, clip))
        // Decode runs on its own thread in real time; pump frames until the overlay reports the end.
        val deadline = System.currentTimeMillis() + 20_000
        EventQueue.invokeAndWait {
            ImageComposeScene(width = 640, height = 360, density = Density(640f / PreviewGeometry.BASE_WIDTH)).use { scene ->
                scene.setContent { XmbFrame(model(PfpThemeManifest.WAVE_ANIMATED), live = spec, onBootFinished = { finished.incrementAndGet() }) }
                var t = 0L
                while (finished.get() == 0 && System.currentTimeMillis() < deadline) {
                    Snapshot.sendApplyNotifications()
                    scene.render(t)
                    t += 50_000_000L
                    Thread.sleep(50)
                }
            }
        }
        assertEquals(1, finished.get())
    }

    @Test
    fun `a motion wallpaper replaces the poster while it plays, and a frozen style keeps the poster`() {
        val clip = File.createTempFile("motion-preview", ".mp4").also {
            it.deleteOnExit()
            MotionTestMedia.writeTestMp4(it, width = 320, height = 240, frames = 8)
        }
        val poster = ImageBitmap(64, 36) // transparent: shows the black scrim, unlike the grey video frames
        fun stateOf(style: String) = StudioState(waveStyle = style, wallpaperBitmap = poster, motionFile = clip)

        fun playOnce(style: String, untilDiffers: ByteArray?): ByteArray {
            val state = stateOf(style)
            val spec = PreviewLiveSpec(motionFile = clip)
            var last = ByteArray(0)
            val deadline = System.currentTimeMillis() + 15_000
            EventQueue.invokeAndWait {
                ImageComposeScene(width = 320, height = 180, density = Density(320f / PreviewGeometry.BASE_WIDTH)).use { scene ->
                    scene.setContent { XmbFrame(state.toPreviewModel(), live = spec) }
                    var t = 0L
                    do {
                        Snapshot.sendApplyNotifications()
                        last = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
                        t += 50_000_000L
                        Thread.sleep(50)
                    } while (untilDiffers != null && last.contentEquals(untilDiffers) && System.currentTimeMillis() < deadline)
                }
            }
            return last
        }

        val posterOnly = playOnce(PfpThemeManifest.WAVE_STATIC, untilDiffers = null)
        val frozenAgain = playOnce(PfpThemeManifest.WAVE_REDUCED_STATIC, untilDiffers = null)
        assertContentEquals(posterOnly, frozenAgain, "frozen styles request no frames: the poster, always")
        val playing = playOnce(PfpThemeManifest.WAVE_ANIMATED, untilDiffers = posterOnly)
        assertFalse(playing.contentEquals(posterOnly), "a decoded video frame replaced the poster")
        assertTrue(playing.isNotEmpty())
    }
}
