package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.domain.artwork.ArtworkRelinkTrigger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RescanTriggerBusTest {
    // Relaxed mock: discover() is suspend, returning a data class — relaxed auto-answers it.
    private val discoveryScanner = mockk<RomRootDiscoveryScanner>(relaxed = true)

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
        val bus = RescanTriggerBus(scanner, discoveryScanner, this)

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
        val bus = RescanTriggerBus(scanner, discoveryScanner, this)

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
        val bus = RescanTriggerBus(scanner, discoveryScanner, this)

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
        val bus = RescanTriggerBus(scanner, discoveryScanner, this)

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
        val bus = RescanTriggerBus(scanner, discoveryScanner, this) { now }

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
        val bus = RescanTriggerBus(scanner, discoveryScanner, this) { now }

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
        val bus = RescanTriggerBus(scanner, discoveryScanner, busScope)

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

    // ── Console discovery ahead of the incremental scan ───────────────────

    @Test
    fun `discovery runs before the incremental scan on every trigger`() = runTest {
        val order = mutableListOf<String>()
        val scanner = mockk<LibraryScanner>(relaxed = true)
        coEvery { scanner.scanAllEnabled(true) } coAnswers {
            order.add("scanAllEnabled")
            listOf(outcome)
        }
        coEvery { discoveryScanner.discover() } coAnswers {
            order.add("discover")
            RomRootDiscoveryScanner.Report(0, emptySet(), 0, 0, 0)
        }
        val bus = RescanTriggerBus(scanner, discoveryScanner, this)

        bus.submit(RescanTrigger.MediaMounted)
        advanceUntilIdle()

        assertEquals(listOf("discover", "scanAllEnabled"), order)
    }

    @Test
    fun `discovery failure does not block the incremental scan`() = runTest {
        val scanner = mockk<LibraryScanner>(relaxed = true)
        coEvery { scanner.scanAllEnabled(true) } returns listOf(outcome)
        coEvery { discoveryScanner.discover() } throws IllegalStateException("saf revoked")
        val bus = RescanTriggerBus(scanner, discoveryScanner, this)

        bus.submit(RescanTrigger.AppResumed)
        advanceUntilIdle()

        coVerify(exactly = 1) { scanner.scanAllEnabled(true) }
    }

    // ── Artwork relink trigger (C22 task T5) ──────────────────────────────────

    private fun outcomeFor(platformId: String, added: Int) = outcome.copy(platformId = platformId, added = added)

    @Test
    fun `a scan that adds nothing triggers no artwork work at all`() = runTest {
        val scanner = mockk<LibraryScanner>(relaxed = true)
        coEvery { scanner.scanAllEnabled(true) } returns listOf(outcomeFor("psx", 0), outcomeFor("ps2", 0))
        val relinked = mutableListOf<Set<String>>()
        val bus = RescanTriggerBus(
            scanner, discoveryScanner, this,
            artworkRelink = ArtworkRelinkTrigger { relinked += it },
        )

        bus.submit(RescanTrigger.AppResumed)
        advanceUntilIdle()

        // The common case — a resume with no new ROMs — must cost nothing.
        assertEquals(emptyList<Set<String>>(), relinked)
    }

    @Test
    fun `only the platforms that gained games are relinked`() = runTest {
        val scanner = mockk<LibraryScanner>(relaxed = true)
        coEvery { scanner.scanAllEnabled(true) } returns listOf(
            outcomeFor("psx", 3),
            outcomeFor("ps2", 0),
            outcomeFor("snes", 1),
        )
        val relinked = mutableListOf<Set<String>>()
        val bus = RescanTriggerBus(
            scanner, discoveryScanner, this,
            artworkRelink = ArtworkRelinkTrigger { relinked += it },
        )

        bus.submit(RescanTrigger.AppResumed)
        advanceUntilIdle()

        assertEquals(listOf(setOf("psx", "snes")), relinked)
    }

    @Test
    fun `a failing relink trigger does not fail the scan`() = runTest {
        val scanner = mockk<LibraryScanner>(relaxed = true)
        coEvery { scanner.scanAllEnabled(true) } returns listOf(outcomeFor("psx", 1))
        val bus = RescanTriggerBus(
            scanner, discoveryScanner, this,
            artworkRelink = ArtworkRelinkTrigger { error("artwork is having a bad day") },
        )

        bus.submit(RescanTrigger.AppResumed)
        advanceUntilIdle()

        // The scan itself still ran and completed; the throw was swallowed at the seam.
        coVerify(exactly = 1) { scanner.scanAllEnabled(true) }
    }
}
