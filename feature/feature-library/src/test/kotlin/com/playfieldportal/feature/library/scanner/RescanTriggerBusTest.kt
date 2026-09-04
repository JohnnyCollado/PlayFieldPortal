package com.playfieldportal.feature.library.scanner

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RescanTriggerBusTest {
    private val outcome = PlatformScanOutcome(
        platformId = "psx",
        displayName = "PlayStation",
        status = ScanStatus.COMPLETED,
        added = 0,
        markedMissing = 0,
    )

    @Test
    fun `two mounts inside debounce window produce one scan`() = runTest {
        val scanner = mockk<LibraryScanner>(relaxed = true)
        coEvery { scanner.scanAllEnabled(true) } returns listOf(outcome)
        val bus = RescanTriggerBus(scanner, this)

        bus.submit(RescanTrigger.MediaMounted)
        advanceTimeBy(1_000)
        bus.submit(RescanTrigger.MediaMounted)
        advanceTimeBy(1_999)
        coVerify(exactly = 0) { scanner.scanAllEnabled(true) }
        advanceTimeBy(1)
        advanceUntilIdle()
        coVerify(exactly = 1) { scanner.scanAllEnabled(true) }
    }

    @Test
    fun `resume during an in-flight scan does not start a second`() = runTest {
        val scanner = mockk<LibraryScanner>(relaxed = true)
        coEvery { scanner.scanAllEnabled(true) } coAnswers {
            delay(1_000)
            listOf(outcome)
        }
        val bus = RescanTriggerBus(scanner, this)

        bus.submit(RescanTrigger.AppResumed)
        advanceUntilIdle()
        bus.submit(RescanTrigger.AppResumed)
        advanceUntilIdle()

        coVerify(exactly = 1) { scanner.scanAllEnabled(true) }
    }

    @Test
    fun `unplug edge is not swallowed by resume throttle`() = runTest {
        val scanner = mockk<LibraryScanner>(relaxed = true)
        coEvery { scanner.scanAllEnabled(true) } returns listOf(outcome)
        val bus = RescanTriggerBus(scanner, this)

        bus.submit(RescanTrigger.AppResumed)
        advanceUntilIdle()
        bus.submit(RescanTrigger.UsbDisconnected)
        advanceTimeBy(2_000)
        advanceUntilIdle()

        coVerify(exactly = 2) { scanner.scanAllEnabled(true) }
    }

    @Test
    fun `scanner exception does not kill the bus`() = runTest {
        val scanner = mockk<LibraryScanner>(relaxed = true)
        coEvery { scanner.scanAllEnabled(true) } throws IllegalStateException("boom")
        val bus = RescanTriggerBus(scanner, this)

        bus.submit(RescanTrigger.AppResumed)
        advanceUntilIdle()
        bus.submit(RescanTrigger.AppResumed)
        advanceUntilIdle()

        coVerify(exactly = 1) { scanner.scanAllEnabled(true) }
    }

    // ── Clock-driven resume throttle (A3) ──────────────────────────────────

    @Test
    fun `resume inside throttle window is skipped`() = runTest {
        val scanner = mockk<LibraryScanner>(relaxed = true)
        coEvery { scanner.scanAllEnabled(true) } returns listOf(outcome)
        var now = 100_000L
        val bus = RescanTriggerBus(scanner, this) { now }

        bus.submit(RescanTrigger.AppResumed)
        advanceUntilIdle()
        coVerify(exactly = 1) { scanner.scanAllEnabled(true) }

        // 4 minutes later — still inside the 5-minute throttle.
        now += RescanTriggerBus.RESUME_THROTTLE_MS - 60_000
        bus.submit(RescanTrigger.AppResumed)
        advanceUntilIdle()
        coVerify(exactly = 1) { scanner.scanAllEnabled(true) }
    }

    @Test
    fun `resume past the throttle boundary runs again`() = runTest {
        val scanner = mockk<LibraryScanner>(relaxed = true)
        coEvery { scanner.scanAllEnabled(true) } returns listOf(outcome)
        var now = 100_000L
        val bus = RescanTriggerBus(scanner, this) { now }

        bus.submit(RescanTrigger.AppResumed)
        advanceUntilIdle()
        coVerify(exactly = 1) { scanner.scanAllEnabled(true) }

        // Cross the boundary: a resume at/after first + RESUME_THROTTLE_MS runs again.
        now += RescanTriggerBus.RESUME_THROTTLE_MS
        bus.submit(RescanTrigger.AppResumed)
        advanceUntilIdle()
        coVerify(exactly = 2) { scanner.scanAllEnabled(true) }
    }

    @Test
    fun `cancelling the scope stops the in-flight scan`() = runTest {
        val scanner = mockk<LibraryScanner>(relaxed = true)
        coEvery { scanner.scanAllEnabled(true) } coAnswers {
            delay(10_000)
            listOf(outcome)
        }
        // TestScheduler is itself a CoroutineContext, so it can back the scope directly.
        val busScope = kotlinx.coroutines.CoroutineScope(testScheduler)
        val bus = RescanTriggerBus(scanner, busScope)

        bus.submit(RescanTrigger.AppResumed)
        advanceTimeBy(1_000)
        // The scan started and is suspended mid-flight (the 10 s scan delay hasn't elapsed).
        coVerify(exactly = 1) { scanner.scanAllEnabled(true) }

        busScope.cancel()
        advanceUntilIdle()
        // The scan coroutine was cancelled with the scope, and a later trigger can neither crash
        // (launch on a cancelled scope is a no-op) nor start a new scan.
        bus.submit(RescanTrigger.AppResumed)
        advanceUntilIdle()
        coVerify(exactly = 1) { scanner.scanAllEnabled(true) }
    }
}
