package com.playfieldportal.feature.launcher

import com.playfieldportal.core.data.repository.GameBootPreferences
import com.playfieldportal.core.data.repository.UiMediaStore
import io.mockk.every
import io.mockk.mockk
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
 * The gate's whole job is that a launch is never lost: disabled means no wait at all, a stalled
 * presentation times out and proceeds, and a second request while one is on screen is dropped
 * rather than queued. All three are pinned here; the overlay itself is Compose + ExoPlayer and is
 * verified on device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameBootGateTest {

    private fun gate(enabled: Boolean): GameBootGate {
        val prefs: GameBootPreferences = mockk(relaxed = true) {
            every { gameBootEnabledFlow } returns flowOf(enabled)
        }
        val store: UiMediaStore = mockk(relaxed = true) {
            every { pathFor(any()) } returns null
        }
        return GameBootGate(prefs, store)
    }

    /**
     * awaitPresentation reads the media paths through withContext(Dispatchers.IO), which real
     * threads cannot be driven by the test scheduler — so assertions after it must poll with a
     * real sleep in between (same pattern as AudioSettingsViewModelTest.eventually).
     *
     * runCurrent(), not advanceUntilIdle(): advancing virtual time also fires the gate's 7 s
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
    fun `disabled returns immediately and never raises a request`() = runTest {
        val gate = gate(enabled = false)

        gate.awaitPresentation("Crash Bandicoot")

        assertNull(gate.active.value, "A disabled gate must never put a presentation on screen")
        assertFalse(gate.isActive)
    }

    @Test
    fun `enabled suspends until the overlay reports back`() = runTest {
        val gate = gate(enabled = true)

        val awaiting = async { gate.awaitPresentation("Crash Bandicoot") }
        eventually("the presentation request is raised") { gate.active.value != null }

        assertNotNull(gate.active.value, "The overlay should have been asked to present")
        assertTrue(awaiting.isActive, "The launch must still be waiting")

        gate.onPresentationFinished()
        advanceUntilIdle()

        assertTrue(awaiting.isCompleted)
        assertNull(gate.active.value, "The request must be cleared once the presentation ends")
    }

    @Test
    fun `a stalled presentation times out and proceeds with the launch`() = runTest {
        val gate = gate(enabled = true)

        val awaiting = async { gate.awaitPresentation("Crash Bandicoot") }
        eventually("the presentation request is raised") { gate.active.value != null }
        assertTrue(awaiting.isActive)

        // Nothing ever calls onPresentationFinished — the watchdog has to do it.
        advanceTimeBy(GameBootGate.TIMEOUT_MS + 1)
        advanceUntilIdle()

        // Completed, NOT failed: a timeout must not propagate as an exception into the launch.
        assertTrue(awaiting.isCompleted)
        awaiting.await()
        assertNull(gate.active.value)
    }

    @Test
    fun `a second request while presenting is dropped rather than queued`() = runTest {
        val gate = gate(enabled = true)

        val first = async { gate.awaitPresentation("Crash Bandicoot") }
        eventually("the presentation request is raised") { gate.active.value != null }
        val firstRequest = gate.active.value

        // Mashing Confirm: the second launch must not wait behind, or replace, the first.
        launch { gate.awaitPresentation("Spyro") }
        // runCurrent, not advanceUntilIdle: advancing virtual time would fire the FIRST
        // request's 7 s watchdog, clearing it — and then the second request, no longer seeing
        // one on screen, would legitimately start. The drop-while-active rule needs the first
        // request to still be alive.
        testScheduler.runCurrent()

        assertTrue(gate.active.value === firstRequest, "The on-screen presentation must not change")

        gate.onPresentationFinished()
        advanceUntilIdle()
        assertTrue(first.isCompleted)
    }
}
