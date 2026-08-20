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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verification obligations from docs/adr/0001-library-scanner-owns-rom-survey.md.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryScannerTest {

    private lateinit var gameRepository: GameRepository
    private lateinit var memoryCardRepository: MemoryCardRepository
    private lateinit var reconciler: LibraryReconciler
    private lateinit var scanSourceResolver: ScanSourceResolver
    private lateinit var tombstoneDao: ScanTombstoneDao
    private lateinit var discSetBuilder: DiscSetBuilder
    private lateinit var m3uPlaylistReader: M3uPlaylistReader

    private val card = MemoryCard(
        platformId = "psx",
        displayName = "PlayStation Memory Card",
        enabled = true,
        treeUri = "content://tree/psx",
        supportedExtensions = listOf("bin"),
    )

    private fun completeSource(
        newGames: List<Game> = emptyList(),
        present: Set<String>? = emptySet(),
    ): (Set<String>) -> Flow<ScanResult> = {
        flowOf(
            ScanResult.Complete(
                newGames = newGames,
                alreadyInLibrary = 0,
                unmatched = emptyList(),
                requiresUserAssignment = emptyList(),
                presentRomPaths = present,
            )
        )
    }

    @Before
    fun setUp() {
        gameRepository = mockk(relaxed = true)
        memoryCardRepository = mockk(relaxed = true)
        reconciler = mockk(relaxed = true)
        scanSourceResolver = mockk(relaxed = true)
        tombstoneDao = mockk(relaxed = true)
        // The real builder: the reconciliation under test derives over existing + new rows.
        discSetBuilder = DiscSetBuilder()
        // The reader is context-backed (SAF URIs) — mocked; the builder's caller seam stays real.
        m3uPlaylistReader = mockk(relaxed = true)

        coEvery { memoryCardRepository.getById("psx") } returns card
        coEvery { memoryCardRepository.getAll() } returns listOf(card)
        coEvery { gameRepository.observeByPlatform("psx") } returns flowOf(emptyList())
        coEvery { tombstoneDao.getPathsForPlatform("psx") } returns emptyList()
        coEvery { reconciler.reconcile(any(), any(), any(), any()) } returns
            LibraryReconciler.Result(markedSeen = 0, markedMissing = 0, skipped = false)
    }

    // A TestDispatcher needs a live TestScheduler from the runTest coroutine, so the scanner is
    // built per-test (inside runTest) rather than in setUp().
    private fun TestScope.scannerFor(
        gameRepository: GameRepository = this@LibraryScannerTest.gameRepository,
        tombstoneDao: ScanTombstoneDao = this@LibraryScannerTest.tombstoneDao,
    ) = LibraryScanner(
        memoryCardRepository,
        gameRepository,
        scanSourceResolver,
        ExistingRomPathResolver(gameRepository, tombstoneDao),
        reconciler,
        // Real reconciler over the real builder + mocked reader, so the integration test drives
        // the actual union derivation while playlist reads stay context-free.
        DiscSetReconciler(discSetBuilder, m3uPlaylistReader, gameRepository),
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `duplicate paths across two sources are upserted once`() = runTest {
        val scanner = scannerFor()
        val game = Game(title = "Crash", platformId = "psx", romPath = "/roms/psx/crash.bin")
        coEvery { scanSourceResolver.sourcesFor(card) } returns listOf(
            completeSource(newGames = listOf(game), present = setOf("/roms/psx/crash.bin")),
            completeSource(newGames = emptyList(), present = setOf("/roms/psx/crash.bin")),
        )

        val outcome = scanner.scanPlatform("psx", removeMissing = false)

        assertEquals(1, outcome.added)
        coVerify(exactly = 1) { gameRepository.upsert(game) }
    }

    @Test
    fun `tombstoned paths are never re-added`() = runTest {
        val scanner = scannerFor()
        coEvery { tombstoneDao.getPathsForPlatform("psx") } returns listOf("/roms/psx/removed.bin")
        // The source is handed `existing`, which must already contain the tombstoned path.
        var seenExisting: Set<String>? = null
        coEvery { scanSourceResolver.sourcesFor(card) } returns listOf({ existing ->
            seenExisting = existing
            flowOf(ScanResult.Complete(emptyList(), 0, emptyList(), emptyList(), presentRomPaths = emptySet()))
        })

        scanner.scanPlatform("psx", removeMissing = false)

        assertTrue("/roms/psx/removed.bin" in seenExisting.orEmpty())
    }

    @Test
    fun `a null present set from any source disables removals for the whole platform`() = runTest {
        val scanner = scannerFor()
        coEvery { scanSourceResolver.sourcesFor(card) } returns listOf(
            completeSource(present = setOf("/roms/psx/a.bin")),
            completeSource(present = null),
        )

        val outcome = scanner.scanPlatform("psx", removeMissing = true)

        assertFalse(outcome.surveyTrusted)
        coVerify(exactly = 1) { reconciler.reconcile(any(), isNull(), any(), any()) }
    }

    @Test
    fun `a scan error disables removals even when other sources surveyed cleanly`() = runTest {
        val scanner = scannerFor()
        coEvery { scanSourceResolver.sourcesFor(card) } returns listOf(
            completeSource(present = setOf("/roms/psx/a.bin")),
            { flowOf(ScanResult.Error("card went away mid-walk")) },
        )

        val outcome = scanner.scanPlatform("psx", removeMissing = true)

        assertFalse(outcome.surveyTrusted)
        assertEquals("card went away mid-walk", outcome.errorMessage)
        coVerify(exactly = 1) { reconciler.reconcile(any(), any(), eq(true), any()) }
    }

    @Test
    fun `recordScan and recountGames only fire when something changed`() = runTest {
        val scanner = scannerFor()
        coEvery { scanSourceResolver.sourcesFor(card) } returns listOf(completeSource())

        scanner.scanPlatform("psx", removeMissing = true)

        coVerify(exactly = 0) { memoryCardRepository.recordScan(any(), any()) }
        coVerify(exactly = 0) { memoryCardRepository.recountGames(any()) }
    }

    @Test
    fun `recordScan and recountGames fire when a new ROM was added`() = runTest {
        val scanner = scannerFor()
        val game = Game(title = "Crash", platformId = "psx", romPath = "/roms/psx/crash.bin")
        coEvery { scanSourceResolver.sourcesFor(card) } returns listOf(completeSource(newGames = listOf(game)))

        scanner.scanPlatform("psx", removeMissing = false)

        coVerify(exactly = 1) { memoryCardRepository.recordScan("psx", any()) }
        coVerify(exactly = 1) { memoryCardRepository.recountGames("psx") }
    }

    @Test
    fun `a database read failure fails the card before any source is surveyed`() = runTest {
        val scanner = scannerFor()
        coEvery { gameRepository.observeByPlatform("psx") } returns flow { throw RuntimeException("db closed") }

        val outcome = scanner.scanPlatform("psx", removeMissing = false)

        assertEquals(ScanStatus.FAILED, outcome.status)
        assertFalse(outcome.surveyTrusted)
        coVerify(exactly = 0) { scanSourceResolver.sourcesFor(any()) }
        coVerify(exactly = 0) { gameRepository.upsert(any()) }
    }

    @Test
    fun `a tombstone read failure fails the card before any source is surveyed`() = runTest {
        val scanner = scannerFor()
        coEvery { tombstoneDao.getPathsForPlatform("psx") } throws RuntimeException("db closed")

        val outcome = scanner.scanPlatform("psx", removeMissing = false)

        assertEquals(ScanStatus.FAILED, outcome.status)
        assertFalse(outcome.surveyTrusted)
        coVerify(exactly = 0) { scanSourceResolver.sourcesFor(any()) }
    }

    @Test
    fun `an unexpected source exception becomes a FAILED untrusted outcome`() = runTest {
        val scanner = scannerFor()
        coEvery { scanSourceResolver.sourcesFor(card) } returns listOf({
            flow<ScanResult> { throw RuntimeException("provider exploded") }
        })

        val outcome = scanner.scanPlatform("psx", removeMissing = false)

        assertEquals(ScanStatus.FAILED, outcome.status)
        assertFalse(outcome.surveyTrusted)
    }

    @Test
    fun `a partial upsert failure keeps prior writes, skips reconciliation, and reports the failure`() = runTest {
        val scanner = scannerFor()
        val ok = Game(title = "Crash", platformId = "psx", romPath = "/roms/psx/crash.bin")
        val bad = Game(title = "Spyro", platformId = "psx", romPath = "/roms/psx/spyro.bin")
        coEvery { gameRepository.upsert(ok) } returns 1L
        coEvery { gameRepository.upsert(bad) } throws RuntimeException("disk full")
        coEvery { scanSourceResolver.sourcesFor(card) } returns
            listOf(completeSource(newGames = listOf(ok, bad), present = setOf("/roms/psx/crash.bin", "/roms/psx/spyro.bin")))

        val outcome = scanner.scanPlatform("psx", removeMissing = true)

        assertEquals(ScanStatus.FAILED, outcome.status)
        assertEquals(1, outcome.added)
        assertEquals("disk full", outcome.errorMessage)
        coVerify(exactly = 0) { reconciler.reconcile(any(), any(), any(), any()) }
    }

    @Test
    fun `manual and triggered requests for the same card share single-flight`() = runTest {
        val scanner = scannerFor()
        val gate = Job()
        coEvery { scanSourceResolver.sourcesFor(card) } returns listOf({
            flow {
                gate.join()
                emit(ScanResult.Complete(emptyList(), 0, emptyList(), emptyList(), presentRomPaths = emptySet()))
            }
        })

        val first = async { scanner.scanPlatform("psx", removeMissing = false) }
        advanceUntilIdle()
        val second = scanner.scanPlatform("psx", removeMissing = false)

        assertEquals(ScanStatus.SKIPPED_BUSY, second.status)
        gate.complete()
        advanceUntilIdle()
        assertEquals(ScanStatus.COMPLETED, first.await().status)
    }

    @Test
    fun `a card with no usable source is skipped explicitly`() = runTest {
        val scanner = scannerFor()
        coEvery { scanSourceResolver.sourcesFor(card) } returns emptyList()

        val outcome = scanner.scanPlatform("psx", removeMissing = false)

        assertEquals(ScanStatus.SKIPPED_NO_SOURCE, outcome.status)
        coVerify(exactly = 0) { gameRepository.upsert(any()) }
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is not converted into an error outcome`() = runTest {
        val scanner = scannerFor()
        coEvery { scanSourceResolver.sourcesFor(card) } returns listOf({
            flow<ScanResult> { throw CancellationException("scope cancelled") }
        })

        scanner.scanPlatform("psx", removeMissing = false)
    }

    @Test
    fun `a later scan adding disc 3 re-derives the existing m3u set`() = runTest {
        // Incremental rescan (plan follow-up): the m3u and discs 1-2 are already in the library
        // with the m3u's set key; disc 3 arrives with the single-pass stale enrichment (its own
        // folder set). The scanner must re-derive the union and upsert the corrected disc 3.
        val scanner = scannerFor()
        val m3uKey = "psx\u0001/roms/psx\u0001Final Fantasy VII"
        val existing = listOf(
            Game(
                title = "Final Fantasy VII", platformId = "psx",
                romPath = "/roms/psx/Final Fantasy VII.m3u",
                discSetKey = m3uKey, discNumber = null, isDiscPrimary = true,
            ),
            Game(
                title = "Final Fantasy VII (Disc 1)", platformId = "psx",
                romPath = "/roms/psx/Final Fantasy VII (Disc 1)/Final Fantasy VII (Disc 1).cue",
                discSetKey = m3uKey, discNumber = 1, isDiscPrimary = false,
            ),
            Game(
                title = "Final Fantasy VII (Disc 2)", platformId = "psx",
                romPath = "/roms/psx/Final Fantasy VII (Disc 2)/Final Fantasy VII (Disc 2).cue",
                discSetKey = m3uKey, discNumber = 2, isDiscPrimary = false,
            ),
        )
        coEvery { gameRepository.observeByPlatform("psx") } returns flowOf(existing)
        val disc3 = Game(
            title = "Final Fantasy VII (Disc 3)", platformId = "psx",
            romPath = "/roms/psx/Final Fantasy VII (Disc 3)/Final Fantasy VII (Disc 3).cue",
            discSetKey = "psx\u0001/roms/psx/Final Fantasy VII (Disc 3)\u0001Final Fantasy VII",
            discNumber = 3, isDiscPrimary = true,
        )
        coEvery { scanSourceResolver.sourcesFor(card) } returns listOf(
            completeSource(newGames = listOf(disc3), present = setOf(disc3.romPath!!)),
        )
        coEvery { m3uPlaylistReader.read(any()) } returns listOf(
            "Final Fantasy VII (Disc 1).cue",
            "Final Fantasy VII (Disc 2).cue",
            "Final Fantasy VII (Disc 3).cue",
        )

        val outcome = scanner.scanPlatform("psx", removeMissing = false)

        assertEquals(ScanStatus.COMPLETED, outcome.status)
        assertEquals(1, outcome.added)
        coVerify(exactly = 1) {
            gameRepository.upsert(match {
                it.romPath == disc3.romPath &&
                    it.discSetKey == m3uKey &&
                    it.discNumber == 3 &&
                    !it.isDiscPrimary
            })
        }
    }

    @Test
    fun `scanAllEnabled skips disabled and sourceless cards without aborting the rest`() = runTest {
        val scanner = scannerFor()
        val disabled = card.copy(platformId = "n64", enabled = false)
        val noSource = card.copy(platformId = "psp", treeUri = null, romDirectory = null)
        val noExtensions = card.copy(platformId = "gba", supportedExtensions = emptyList())
        coEvery { memoryCardRepository.getAll() } returns listOf(card, disabled, noSource, noExtensions)
        coEvery { memoryCardRepository.getById("gba") } returns noExtensions
        coEvery { scanSourceResolver.sourcesFor(card) } returns listOf(completeSource())

        val outcomes = scanner.scanAllEnabled(removeMissing = true)

        assertEquals(1, outcomes.size)
        assertEquals("psx", outcomes.single().platformId)
    }
}
