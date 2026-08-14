package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.data.database.dao.ScanTombstoneDao
import com.playfieldportal.core.data.repository.LibraryReconciler
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.core.domain.repository.GameRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 7 verification for the rescan trigger guards (docs/missing-roms-plan.md).
 *
 * The three guards fail differently, so they are tested separately:
 *  - throttle   -> no wasted folder walks on rapid resume
 *  - debounce   -> a mount broadcast burst causes one scan, not ten
 *  - remount    -> a mount does actually reach the reconcile path
 *
 * [scanCount] counts how many times a scan source was actually invoked, which is the thing that
 * costs battery. Asserting on that rather than on reconcile() calls is deliberate: a guard that
 * lets the folder walk happen and only skips the DB write would still be a regression.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRescanCoordinatorTest {

    private lateinit var gameRepository: GameRepository
    private lateinit var memoryCardRepository: MemoryCardRepository
    private lateinit var reconciler: LibraryReconciler
    private lateinit var scanSourceResolver: ScanSourceResolver
    private lateinit var tombstoneDao: ScanTombstoneDao
    private lateinit var coordinator: LibraryRescanCoordinator

    private val scanCount = AtomicInteger(0)

    private val card = MemoryCard(
        platformId = "psx",
        displayName = "PlayStation",
        enabled = true,
        treeUri = "content://tree/psx",
        supportedExtensions = listOf("bin"),
    )

    // One scan source that reports a single present ROM and bumps the invocation counter.
    private val countingSource: (Set<String>) -> Flow<ScanResult> = {
        scanCount.incrementAndGet()
        flowOf(
            ScanResult.Complete(
                newGames = emptyList(),
                alreadyInLibrary = 1,
                unmatched = emptyList(),
                requiresUserAssignment = emptyList(),
                presentRomPaths = setOf("/roms/psx/crash.bin"),
            )
        )
    }

    @Before
    fun setUp() {
        scanCount.set(0)
        gameRepository = mockk(relaxed = true)
        memoryCardRepository = mockk(relaxed = true)
        reconciler = mockk(relaxed = true)
        scanSourceResolver = mockk(relaxed = true)
        tombstoneDao = mockk(relaxed = true)

        coEvery { memoryCardRepository.getAll() } returns listOf(card)
        coEvery { memoryCardRepository.getById("psx") } returns card
        coEvery { scanSourceResolver.sourcesFor(card) } returns listOf(countingSource)
        coEvery { gameRepository.observeByPlatform("psx") } returns
            flowOf(listOf(Game(id = 1L, title = "Crash", platformId = "psx", romPath = "/roms/psx/crash.bin")))
        coEvery { tombstoneDao.getPathsForPlatform("psx") } returns emptyList()
        coEvery { reconciler.reconcile(any(), any(), any(), any()) } returns
            LibraryReconciler.Result(markedSeen = 1, markedMissing = 0, skipped = false)

        coordinator = LibraryRescanCoordinator(
            gameRepository,
            memoryCardRepository,
            reconciler,
            scanSourceResolver,
            tombstoneDao,
        )
    }

    @Test
    fun `onResume scans the first time`() = runTest {
        coordinator.onResume()
        advanceUntilIdle()

        assertEquals(1, scanCount.get())
        coVerify(exactly = 1) { reconciler.reconcile(any(), any(), any(), any()) }
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

        assertEquals(1, scanCount.get())
        coVerify(exactly = 1) { reconciler.reconcile(any(), any(), any(), any()) }
    }

    @Test
    fun `many rapid resumes still only scan once`() = runTest {
        repeat(10) {
            coordinator.onResume()
            advanceUntilIdle()
        }

        assertEquals(1, scanCount.get())
    }

    @Test
    fun `a mount triggers a scan after the debounce`() = runTest {
        // The remount-reconciles path: mount -> quiet window -> scan + reconcile.
        coordinator.onMediaMounted()
        advanceUntilIdle()

        assertEquals(1, scanCount.get())
        coVerify(exactly = 1) { reconciler.reconcile(any(), any(), any(), any()) }
    }

    @Test
    fun `a burst of mount broadcasts collapses into one scan`() = runTest {
        // A single card mount fires several broadcasts (one per volume, sometimes duplicated).
        // Launched concurrently so they overlap inside the debounce window, as they do in reality.
        repeat(5) { launch { coordinator.onMediaMounted() } }
        advanceUntilIdle()

        assertEquals(1, scanCount.get())
        coVerify(exactly = 1) { reconciler.reconcile(any(), any(), any(), any()) }
    }

    @Test
    fun `a mount bypasses the resume throttle`() = runTest {
        // Mount is the strong signal: it must still scan even though a resume just ran, otherwise
        // ROMs copied from a PC would not appear until the throttle expired.
        coordinator.onResume()
        advanceUntilIdle()
        assertEquals(1, scanCount.get())

        coordinator.onMediaMounted()
        advanceUntilIdle()

        assertEquals(2, scanCount.get())
    }

    @Test
    fun `cards with no scannable folder are skipped`() = runTest {
        // Neither a SAF grant nor a legacy raw path means there is nothing to walk; such a card
        // must not reach the resolver at all.
        val unconfigured = card.copy(platformId = "n64", treeUri = null, romDirectory = null)
        coEvery { memoryCardRepository.getAll() } returns listOf(unconfigured)

        coordinator.onResume()
        advanceUntilIdle()

        assertEquals(0, scanCount.get())
        coVerify(exactly = 0) { reconciler.reconcile(any(), any(), any(), any()) }
    }

    @Test
    fun `disabled cards are skipped`() = runTest {
        coEvery { memoryCardRepository.getAll() } returns listOf(card.copy(enabled = false))

        coordinator.onResume()
        advanceUntilIdle()

        assertEquals(0, scanCount.get())
    }

    @Test
    fun `a scan error is passed to the reconciler so it can bail`() = runTest {
        // The coordinator must not swallow the error — the reconciler is what decides to skip, and
        // it can only do that if scanErrored arrives as true.
        coEvery { scanSourceResolver.sourcesFor(card) } returns listOf({
            flowOf(ScanResult.Error("card went away mid-walk"))
        })

        coordinator.onResume()
        advanceUntilIdle()

        coVerify(exactly = 1) { reconciler.reconcile(any(), any(), eq(true), any()) }
    }

    @Test
    fun `a source that cannot survey yields a null present-set`() = runTest {
        // presentRomPaths == null means "the walk never ran"; it must reach the reconciler as null
        // rather than as an empty set, or the library would be flagged wholesale missing.
        coEvery { scanSourceResolver.sourcesFor(card) } returns listOf({
            flowOf(
                ScanResult.Complete(
                    newGames = emptyList(),
                    alreadyInLibrary = 0,
                    unmatched = emptyList(),
                    requiresUserAssignment = emptyList(),
                    presentRomPaths = null,
                )
            )
        })

        coordinator.onResume()
        advanceUntilIdle()

        coVerify(exactly = 1) { reconciler.reconcile(any(), isNull(), any(), any()) }
    }
}
