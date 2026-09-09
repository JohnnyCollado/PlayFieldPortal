package com.playfieldportal.feature.launcher

import android.content.Context
import com.playfieldportal.core.data.repository.GameBootPreferences
import com.playfieldportal.core.data.repository.UiMediaStore
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.ui.media.UiMediaAudioPlayer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gate's whole job is that a launch is never lost: switched off means no wait at all, a
 * presentation times out and proceeds if it stalls, and a second request while one is on screen is
 * dropped rather than queued. All are pinned here; the overlay itself is Compose + ExoPlayer and is
 * verified on device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameBootGateTest {

    private class Harness(val scope: TestScope, enabled: Boolean, customVideo: String? = null) {
        val prefs: GameBootPreferences = mockk(relaxed = true) {
            every { gameBootEnabledFlow } returns flowOf(enabled)
        }
        val store: UiMediaStore = mockk(relaxed = true) {
            every { pathFor(any()) } returns null
            every { pathFor(UiMediaSlot.GAMEBOOT_VIDEO) } returns customVideo
        }
        val context: Context = mockk(relaxed = true) {
            every { packageName } returns "com.test"
        }
        val player: UiMediaAudioPlayer = mockk(relaxed = true)

        val gate = GameBootGate(context, prefs, store, player)
    }

    /**
     * awaitPresentation reads the media paths through withContext(Dispatchers.IO), which real
     * threads cannot be driven by the test scheduler — so assertions after it must poll with a
     * real sleep in between (same pattern as AudioSettingsViewModelTest.eventually).
     *
     * runCurrent(), not advanceUntilIdle(): advancing virtual time also fires the gate's 8 s
     * watchdog, which CLEARS the presentation — the request would appear and vanish inside one
     * advance. runCurrent only drains tasks at the current virtual time.
     */
    private fun TestScope.eventually(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out waiting for: $what")
            }
            Thread.sleep(20)
            testScheduler.runCurrent()
        }
    }

    @Test
    fun `switched off returns immediately and never raises a request or plays`() = runTest {
        val h = Harness(this, enabled = false)

        h.gate.awaitPresentation("Crash Bandicoot")

        assertNull(h.gate.active.value, "A disabled gate must never put a presentation on screen")
        assertFalse(h.gate.isActive)
        verify(exactly = 0) { h.player.play(any<String>(), any(), any()) }
    }

    @Test
    fun `switched on suspends until the overlay reports back and plays the built-in sound`() = runTest {
        val h = Harness(this, enabled = true)

        val awaiting = async { h.gate.awaitPresentation("Crash Bandicoot") }
        eventually("the presentation request is raised") { h.gate.active.value != null }

        val request = assertNotNull(h.gate.active.value, "The overlay should have been asked to present")
        // With no custom clip the request must carry the built-in sound — the overlay never
        // grows a fallback branch.
        assertTrue(
            request.audioPath?.startsWith("android.resource://com.test/") == true,
            "No custom clip must resolve the built-in audio, got: ${request.audioPath}",
        )
        // The audio is gate-owned: it starts before the first frame so the measured timeline
        // stays in sync, and the draw-only overlay can never release the player mid-clip.
        verify(exactly = 1) { h.player.play(any<String>(), any(), any()) }
        assertTrue(awaiting.isActive, "The launch must still be waiting")

        h.gate.onPresentationFinished()
        advanceUntilIdle()

        assertTrue(awaiting.isCompleted)
        assertNull(h.gate.active.value, "The request must be cleared once the presentation ends")
    }

    @Test
    fun `a custom clip replaces the whole presentation and keeps its own track`() = runTest {
        val h = Harness(this, enabled = true, customVideo = "/data/ui-media/gameboot_video.mp4")

        val awaiting = async { h.gate.awaitPresentation("Crash Bandicoot") }
        eventually("the presentation request is raised") { h.gate.active.value != null }

        val request = assertNotNull(h.gate.active.value)
        assertTrue(request.videoPath == "/data/ui-media/gameboot_video.mp4")
        // Scoring someone's clip with the built-in sound is never what they meant.
        assertNull(request.audioPath, "A custom clip must keep its own audio track")
        verify(exactly = 0) { h.player.play(any<String>(), any(), any()) }

        h.gate.onPresentationFinished()
        advanceUntilIdle()
        assertTrue(awaiting.isCompleted)
    }

    @Test
    fun `a stalled presentation times out and proceeds with the launch`() = runTest {
        val h = Harness(this, enabled = true)

        val awaiting = async { h.gate.awaitPresentation("Crash Bandicoot") }
        eventually("the presentation request is raised") { h.gate.active.value != null }
        assertTrue(awaiting.isActive)

        // Nothing ever calls onPresentationFinished — the watchdog has to do it.
        advanceTimeBy(GameBootGate.TIMEOUT_MS + 1)
        advanceUntilIdle()

        // Completed, NOT failed: a timeout must not propagate as an exception into the launch.
        assertTrue(awaiting.isCompleted)
        awaiting.await()
        assertNull(h.gate.active.value)
    }

    @Test
    fun `a second request while presenting is dropped rather than queued`() = runTest {
        val h = Harness(this, enabled = true)

        val first = async { h.gate.awaitPresentation("Crash Bandicoot") }
        eventually("the presentation request is raised") { h.gate.active.value != null }
        val firstRequest = h.gate.active.value

        // Mashing Confirm: the second launch must not wait behind, or replace, the first.
        launch { h.gate.awaitPresentation("Spyro") }
        // runCurrent, not advanceUntilIdle: advancing virtual time would fire the FIRST
        // request's 8 s watchdog, clearing it — and then the second request, no longer seeing
        // one on screen, would legitimately start. The drop-while-active rule needs the first
        // request to still be alive.
        testScheduler.runCurrent()

        assertTrue(h.gate.active.value === firstRequest, "The on-screen presentation must not change")

        h.gate.onPresentationFinished()
        advanceUntilIdle()
        assertTrue(first.isCompleted)
    }
}
