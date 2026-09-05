package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.data.platform.PlatformFolderHintResolver
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.core.domain.model.Platform
import com.playfieldportal.core.domain.repository.GameRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RomRootDiscoveryScannerTest {
    // childDocIdOf / rawPathOfTree are companion helpers over DocumentsContract + Uri —
    // untestable on the JVM, so the companion is mocked and the doc-id math is stubbed.
    private val romRootRepository = mockk<RomRootRepository>(relaxed = true)

    private val gbcPlatform = Platform(
        id = "gbc",
        name = "Game Boy Color",
        shortName = "GBC",
        iconRes = null,
        accentColor = 0xFF6A5ACD,
        romExtensions = listOf("gbc", "gb"),
    )

    private val memoryCardRepository = mockk<MemoryCardRepository>(relaxed = true)
    private val romScanner = mockk<RomScanner>(relaxed = true)
    private val gameRepository = mockk<GameRepository>(relaxed = true)
    private val existingRomPathResolver = mockk<ExistingRomPathResolver>(relaxed = true)
    private val discSetReconciler = mockk<DiscSetReconciler>(relaxed = true)

    private val root = "content://com.android.externalstorage.documents/tree/primary%3ARoms"

    @Before
    fun setUp() {
        mockkObject(RomRootRepository.Companion)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun scanner() = RomRootDiscoveryScanner(
        memoryCardRepository = memoryCardRepository,
        romRootRepository = romRootRepository,
        romScanner = romScanner,
        gameRepository = gameRepository,
        folderHintResolver = PlatformFolderHintResolver(),
        existingRomPathResolver = existingRomPathResolver,
        discSetReconciler = discSetReconciler,
    )

    private fun stubCatalog(vararg platforms: Platform) {
        coEvery { memoryCardRepository.availablePlatformCatalog() } returns platforms.toList()
    }

    private fun stubFolder(vararg subfolders: String) {
        coEvery { romScanner.listSubfolderNames(root) } returns subfolders.toList()
        for (name in subfolders) {
            every { RomRootRepository.childDocIdOf(root, name) } returns "primary:Roms/$name"
        }
    }

    private fun stubScan(platformId: String, newGames: List<Game> = listOf(Game(title = "New Game", platformId = platformId))) {
        coEvery { existingRomPathResolver.baselineFor(platformId) } returns
            ExistingRomPathResolver.Baseline(emptyList(), emptySet())
        coEvery {
            romScanner.scanTree(root, any(), platformId, true, emptySet(), startDocId = any())
        } returns flow {
            emit(
                ScanResult.Complete(
                    newGames = newGames,
                    alreadyInLibrary = 0,
                    unmatched = emptyList(),
                    requiresUserAssignment = emptyList(),
                    presentRomPaths = setOf("/storage/emulated/0/Roms/$platformId/game.rom"),
                )
            )
        }
    }

    private fun stubNoRoms(platformId: String) {
        coEvery { existingRomPathResolver.baselineFor(platformId) } returns
            ExistingRomPathResolver.Baseline(emptyList(), emptySet())
        coEvery {
            romScanner.scanTree(root, any(), platformId, true, emptySet(), startDocId = any())
        } returns flow {
            emit(
                ScanResult.Complete(
                    newGames = emptyList(),
                    alreadyInLibrary = 0,
                    unmatched = emptyList(),
                    requiresUserAssignment = emptyList(),
                    presentRomPaths = emptySet(),
                )
            )
        }
    }

    // ── Discovery: new console created and scanned ─────────────────────────

    @Test
    fun `folder with roms and no card creates a card and scans it`() = runTest {
        stubCatalog(gbcPlatform)
        coEvery { romRootRepository.getAll() } returns listOf(root)
        stubFolder("gbc")
        stubScan("gbc")
        coEvery { memoryCardRepository.getAll() } returns emptyList()
        coEvery { memoryCardRepository.getById("gbc") } returns null

        val report = scanner().discover()

        coVerify(exactly = 1) {
            memoryCardRepository.addCard(
                platformId = "gbc",
                displayName = "Game Boy Color Memory Card",
                romDirectory = any(),
                emulatorId = null,
            )
        }
        coVerify(exactly = 1) { gameRepository.upsert(any()) }
        coVerify(exactly = 1) { memoryCardRepository.recordScan("gbc", any()) }
        assertTrue(report.discoveredPlatforms.contains("gbc"))
        assertEquals(1, report.totalAdded)
        assertEquals(1, report.newCards)
    }

    // ── Existing card: folder is surveyed, no new card ─────────────────────

    @Test
    fun `folder whose card already exists does not create a second card`() = runTest {
        stubCatalog(gbcPlatform)
        coEvery { romRootRepository.getAll() } returns listOf(root)
        stubFolder("gbc")
        stubScan("gbc")
        coEvery { memoryCardRepository.getAll() } returns listOf(
            MemoryCard(platformId = "gbc", displayName = "GBC Memory Card"),
        )
        coEvery { memoryCardRepository.getById("gbc") } returns
            MemoryCard(platformId = "gbc", displayName = "GBC Memory Card")

        val report = scanner().discover()

        coVerify(exactly = 0) { memoryCardRepository.addCard(any(), any(), any(), any()) }
        coVerify(exactly = 1) { gameRepository.upsert(any()) }
        assertEquals(0, report.newCards)
    }

    // ── Folder with no ROMs: no card, no scan ──────────────────────────────

    @Test
    fun `empty folder does not create a card`() = runTest {
        stubCatalog(gbcPlatform)
        coEvery { romRootRepository.getAll() } returns listOf(root)
        stubFolder("gbc")
        stubNoRoms("gbc")
        coEvery { memoryCardRepository.getAll() } returns emptyList()
        coEvery { memoryCardRepository.getById("gbc") } returns null

        val report = scanner().discover()

        coVerify(exactly = 0) { memoryCardRepository.addCard(any(), any(), any(), any()) }
        coVerify(exactly = 0) { gameRepository.upsert(any()) }
        assertEquals(0, report.totalAdded)
        assertTrue(report.discoveredPlatforms.isEmpty())
    }

    // ── Folder name doesn't map to a catalog platform: ignored ─────────────

    @Test
    fun `folder name that maps to no platform is skipped`() = runTest {
        stubCatalog(gbcPlatform)
        coEvery { romRootRepository.getAll() } returns listOf(root)
        stubFolder("not-a-system", "gbc")
        stubScan("gbc")
        coEvery { memoryCardRepository.getAll() } returns emptyList()
        coEvery { memoryCardRepository.getById("gbc") } returns null

        val report = scanner().discover()

        // Only gbc becomes a card — the unknown folder is skipped before any platform lookup.
        coVerify(exactly = 1) {
            memoryCardRepository.addCard(platformId = "gbc", displayName = any(), romDirectory = any(), emulatorId = null)
        }
        coVerify(exactly = 1) { gameRepository.upsert(any()) }
        // Both top-level folders are walked, even the one that maps to no platform.
        assertEquals(2, report.scannedFolders)
    }

    // ── Platform with no supported extensions: skipped, never scanned ──────

    @Test
    fun `platform without extensions is skipped`() = runTest {
        val extless = gbcPlatform.copy(id = "extless", romExtensions = emptyList())
        stubCatalog(gbcPlatform, extless)
        coEvery { romRootRepository.getAll() } returns listOf(root)
        stubFolder("gbc", "extless")
        stubScan("gbc")
        coEvery { memoryCardRepository.getAll() } returns emptyList()
        coEvery { memoryCardRepository.getById("gbc") } returns null
        coEvery { memoryCardRepository.getById("extless") } returns null

        val report = scanner().discover()

        // gbc still creates its card; the extension-less extless platform is never scanned.
        coVerify(exactly = 1) {
            memoryCardRepository.addCard(platformId = "gbc", displayName = any(), romDirectory = any(), emulatorId = null)
        }
        coVerify(exactly = 1) { gameRepository.upsert(any()) }
        assertEquals(2, report.scannedFolders)
    }

    // ── No roots configured: no-op ─────────────────────────────────────────

    @Test
    fun `no rom roots configured is a no-op`() = runTest {
        coEvery { romRootRepository.getAll() } returns emptyList()
        coEvery { memoryCardRepository.getAll() } returns emptyList()
        stubCatalog(gbcPlatform)

        val report = scanner().discover()

        coVerify(exactly = 0) { memoryCardRepository.addCard(any(), any(), any(), any()) }
        coVerify(exactly = 0) { gameRepository.upsert(any()) }
        assertEquals(0, report.scannedFolders)
    }

    // ── Baseline failure: folder skipped, never crashes the pass ──────────

    @Test
    fun `baseline failure skips the folder without aborting the pass`() = runTest {
        stubCatalog(gbcPlatform)
        coEvery { romRootRepository.getAll() } returns listOf(root)
        stubFolder("gbc")
        coEvery { existingRomPathResolver.baselineFor("gbc") } throws IllegalStateException("db down")
        coEvery { memoryCardRepository.getAll() } returns emptyList()
        coEvery { memoryCardRepository.getById("gbc") } returns null

        val report = scanner().discover()

        coVerify(exactly = 0) { memoryCardRepository.addCard(any(), any(), any(), any()) }
        coVerify(exactly = 0) { gameRepository.upsert(any()) }
        assertEquals(1, report.skipped)
    }
}
