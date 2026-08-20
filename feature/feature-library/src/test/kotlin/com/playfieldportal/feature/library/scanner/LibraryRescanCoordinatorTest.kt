package com.playfieldportal.feature.library.scanner

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Phase 7 verification for the rescan trigger guards, now scoped to
 * just the throttle/debounce/single-flight decisions — the scan + reconcile mechanics they gate
 * moved to LibraryScannerTest with LibraryScanner (docs/adr/0001-library-scanner-owns-rom-survey.md).
 * This coordinator's only job is deciding WHEN to call libraryScanner.scanAllEnabled(); these tests
 * verify call counts on that seam, nothing about what happens inside a single card's scan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRescanCoordinatorTest {

    private lateinit var libraryScanner: LibraryScanner
    private lateinit var coordinator: LibraryRescanCoordinator

    private val outcome = PlatformScanOutcome(
        platformId = "psx",
        displayName = "PlayStation",
        status = ScanStatus.COMPLETED,
        added = 0,
        markedMissing = 0,
    )

    @Before
    fun setUp() {
        libraryScanner = mockk(relaxed = true)
        coEvery { libraryScanner.scanAllEnabled(true) } returns listOf(outcome)
        coordinator = LibraryRescanCoordinator(libraryScanner)
    }

    @Test
    fun `onResume scans the first time`() = runTest {
        coordinator.onResume()
        advanceUntilIdle()

        coVerify(exactly = 1) { libraryScanner.scanAllEnabled(true) }
    }

    @Test
    fun `a rapid second onResume is throttled away`() = runTest {
        // "Back out of a game, immediately back out again" — the common case the throttle exists
        // for. Real elapsed time here is milliseconds, far inside RESUME_THROTTLE_MS (5 min), so
        // the second call must no-op without walking a single folder.
        coordinator.onResume()
        advanceUntilIdle()
        coordinator.onResume()
        advanceUntilIdle()

        coVerify(exactly = 1) { libraryScanner.scanAllEnabled(true) }
    }

    @Test
    fun `many rapid resumes still only scan once`() = runTest {
        repeat(10) {
            coordinator.onResume()
            advanceUntilIdle()
        }

        coVerify(exactly = 1) { libraryScanner.scanAllEnabled(true) }
    }

    @Test
    fun `a mount triggers a scan after the debounce`() = runTest {
        // The remount-reconciles path: mount -> quiet window -> scan.
        coordinator.onMediaMounted()
        advanceUntilIdle()

        coVerify(exactly = 1) { libraryScanner.scanAllEnabled(true) }
    }

    @Test
    fun `a burst of mount broadcasts collapses into one scan`() = runTest {
        // A single card mount fires several broadcasts (one per volume, sometimes duplicated).
        // Launched concurrently so they overlap inside the debounce window, as they do in reality.
        repeat(5) { launch { coordinator.onMediaMounted() } }
        advanceUntilIdle()

        coVerify(exactly = 1) { libraryScanner.scanAllEnabled(true) }
    }

    @Test
    fun `a mount bypasses the resume throttle`() = runTest {
        // Mount is the strong signal: it must still scan even though a resume just ran, otherwise
        // ROMs copied from a PC would not appear until the throttle expired.
        coordinator.onResume()
        advanceUntilIdle()
        coordinator.onMediaMounted()
        advanceUntilIdle()

        coVerify(exactly = 2) { libraryScanner.scanAllEnabled(true) }
    }
}
