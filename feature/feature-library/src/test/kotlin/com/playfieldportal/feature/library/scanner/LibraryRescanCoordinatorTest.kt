package com.playfieldportal.feature.library.scanner

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRescanCoordinatorTest {
    private lateinit var libraryScanner: LibraryScanner

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
    }

    private fun coordinator(scope: kotlinx.coroutines.CoroutineScope) =
        LibraryRescanCoordinator(libraryScanner, scope)

    @Test
    fun `onResume scans the first time`() = runTest {
        coordinator(this).onResume()
        advanceUntilIdle()
        coVerify(exactly = 1) { libraryScanner.scanAllEnabled(true) }
    }

    @Test
    fun `a rapid second onResume is throttled away`() = runTest {
        val coordinator = coordinator(this)
        coordinator.onResume()
        advanceUntilIdle()
        coordinator.onResume()
        advanceUntilIdle()
        coVerify(exactly = 1) { libraryScanner.scanAllEnabled(true) }
    }

    @Test
    fun `many rapid resumes still only scan once`() = runTest {
        val coordinator = coordinator(this)
        repeat(10) {
            coordinator.onResume()
            advanceUntilIdle()
        }
        coVerify(exactly = 1) { libraryScanner.scanAllEnabled(true) }
    }

    @Test
    fun `a mount triggers a scan after the debounce`() = runTest {
        coordinator(this).onMediaMounted()
        advanceUntilIdle()
        coVerify(exactly = 1) { libraryScanner.scanAllEnabled(true) }
    }

    @Test
    fun `a burst of mount broadcasts collapses into one scan`() = runTest {
        val coordinator = coordinator(this)
        repeat(5) { coordinator.onMediaMounted() }
        advanceUntilIdle()
        coVerify(exactly = 1) { libraryScanner.scanAllEnabled(true) }
    }

    @Test
    fun `a mount bypasses the resume throttle`() = runTest {
        val coordinator = coordinator(this)
        coordinator.onResume()
        advanceUntilIdle()
        coordinator.onMediaMounted()
        advanceUntilIdle()
        coVerify(exactly = 2) { libraryScanner.scanAllEnabled(true) }
    }
}
