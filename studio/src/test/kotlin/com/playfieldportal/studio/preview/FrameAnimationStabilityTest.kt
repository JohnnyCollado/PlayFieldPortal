package com.playfieldportal.studio.preview

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.playfieldportal.studio.StudioState
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TS-26 spike, automated and headless: is continuous frame animation stable on CMP 1.12 in the
 * Studio's real threading shape? The window's composition lives on the AWT thread, and
 * [PreviewRenderer] hops its offscreen scenes onto that same thread — so here every animation frame
 * is rendered on the AWT thread while offscreen preview renders are requested from another thread.
 *
 * It exercises: infinite transitions, animate*AsState springs and tweens, AnimatedContent
 * enter/exit, M3 CircularProgressIndicator (indeterminate), PrimaryTabRow indicator and SegmentedButton,
 * with state mutated every few frames to force repeated recomposition. A NodeChain/SlotTable
 * corruption surfaces as an exception out of render/setContent; any throwable fails the test.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
class FrameAnimationStabilityTest {

    private val frameNanos = 16_666_667L

    @Test
    fun `frame animations stay stable for thousands of frames beside concurrent preview renders`() {
        var tab by mutableIntStateOf(0)
        var target by mutableIntStateOf(0)
        var page by mutableIntStateOf(0)
        var segment by mutableIntStateOf(0)

        val failure = AtomicReference<Throwable?>(null)
        val rendersDone = java.util.concurrent.atomic.AtomicInteger()
        val previewThread = Thread {
            try {
                val state = StudioState(name = "spike")
                repeat(PREVIEW_RENDERS) {
                    val png = PreviewRenderer.renderPreviewPng(state, widthPx = 640, heightPx = 360)
                    assertTrue(png.size > 100, "preview PNG should be non-trivial")
                    rendersDone.incrementAndGet()
                    Thread.sleep(40)
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }

        var frames = 0
        lateinit var scene: ImageComposeScene
        EventQueue.invokeAndWait {
            scene = ImageComposeScene(width = 480, height = 320).also { scene ->
                scene.setContent {
                    MaterialTheme {
                        Column(Modifier.fillMaxSize()) {
                            val infinite = rememberInfiniteTransition(label = "spike")
                            val pulse by infinite.animateFloat(
                                0.2f, 1f,
                                infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
                                label = "pulse",
                            )
                            val drift by infinite.animateFloat(
                                0f, 40f,
                                infiniteRepeatable(tween(1300), RepeatMode.Restart),
                                label = "drift",
                            )
                            val sprung by animateDpAsState(
                                (target % 4 * 30).dp,
                                spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
                                label = "spring",
                            )
                            val tweened by animateFloatAsState(
                                if (target % 2 == 0) 1f else 0.3f, tween(200), label = "tween",
                            )
                            Box(
                                Modifier.size(20.dp).offset(sprung + drift.dp)
                                    .alpha(pulse * tweened).background(Color.Red),
                            )
                            AnimatedContent(
                                targetState = page,
                                transitionSpec = {
                                    (slideInVertically { it / 2 } + fadeIn(tween(180)))
                                        .togetherWith(slideOutVertically { -it / 2 } + fadeOut(tween(120)))
                                },
                                label = "content",
                            ) { p -> Text("page $p") }
                            CircularProgressIndicator(Modifier.size(32.dp))
                            PrimaryTabRow(selectedTabIndex = tab) {
                                repeat(3) { Tab(tab == it, { }, text = { Text("T$it") }) }
                            }
                            SingleChoiceSegmentedButtonRow {
                                repeat(3) { i ->
                                    SegmentedButton(
                                        selected = segment == i,
                                        onClick = { },
                                        shape = SegmentedButtonDefaults.itemShape(i, 3),
                                    ) { Text("S$i") }
                                }
                            }
                        }
                    }
                }

            }
        }
        try {
            previewThread.start()
            var nanos = 0L
            // One AWT task per frame, like the real event loop, so preview renders hop in between.
            while (frames < FRAMES && failure.get() == null) {
                val f = frames
                EventQueue.invokeAndWait {
                    if (f % 7 == 0) target++
                    if (f % 23 == 0) page++
                    if (f % 31 == 0) tab = (tab + 1) % 3
                    if (f % 17 == 0) segment = (segment + 1) % 3
                    scene.render(nanos)
                }
                nanos += frameNanos
                frames++
            }
        } finally {
            EventQueue.invokeAndWait { scene.close() }
        }
        previewThread.join(30_000)
        assertNull(failure.get(), "preview render threw: ${failure.get()}")
        assertEquals(FRAMES, frames)
        assertEquals(PREVIEW_RENDERS, rendersDone.get())
    }

    /**
     * TS-33: the live preview itself - animated wave, then a motion wallpaper decoding on its own
     * thread (real JCodec, real time) - rendered frame by frame on the AWT thread while offscreen
     * preview.png renders hop in from another thread.
     */
    @Test
    fun `the live preview frame stays stable beside concurrent preview renders`() {
        val clip = java.io.File.createTempFile("stability", ".mp4").also {
            it.deleteOnExit()
            com.playfieldportal.studio.MotionTestMedia.writeTestMp4(it, width = 320, height = 240, frames = 10)
        }
        val failure = AtomicReference<Throwable?>(null)
        val rendersDone = java.util.concurrent.atomic.AtomicInteger()
        val previewThread = Thread {
            try {
                repeat(6) {
                    assertTrue(PreviewRenderer.renderPreviewPng(StudioState(name = "live"), widthPx = 640, heightPx = 360).size > 100)
                    rendersDone.incrementAndGet()
                    Thread.sleep(40)
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }
        val wave = StudioState()
        val motion = StudioState(wallpaperBitmap = androidx.compose.ui.graphics.ImageBitmap(64, 36), motionFile = clip)
        var frames = 0
        previewThread.start()
        for ((state, spec) in listOf(wave to PreviewLiveSpec(), motion to PreviewLiveSpec(motionFile = clip))) {
            lateinit var scene: ImageComposeScene
            EventQueue.invokeAndWait {
                scene = ImageComposeScene(width = 480, height = 270, density = androidx.compose.ui.unit.Density(480f / PreviewGeometry.BASE_WIDTH))
                scene.setContent { XmbFrame(state.toPreviewModel(), live = spec) }
            }
            try {
                var nanos = 0L
                repeat(LIVE_FRAMES) {
                    if (failure.get() != null) return@repeat
                    EventQueue.invokeAndWait {
                        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                        scene.render(nanos)
                    }
                    nanos += frameNanos
                    frames++
                    Thread.sleep(2)
                }
            } finally {
                EventQueue.invokeAndWait { scene.close() }
            }
        }
        previewThread.join(30_000)
        assertNull(failure.get(), "live render threw: ${failure.get()}")
        assertEquals(LIVE_FRAMES * 2, frames)
        assertEquals(6, rendersDone.get())
    }

    private companion object {
        const val FRAMES = 2400
        const val LIVE_FRAMES = 500
        const val PREVIEW_RENDERS = 12
    }
}
