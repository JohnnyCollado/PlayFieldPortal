package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.data.platform.PlatformFolderHintResolver
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.core.domain.model.MemoryCard
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A ROM root that cannot be listed must make the card's survey untrusted rather than silently
 * shrink it: the games under that root were never looked at, so the other roots' results must not
 * flag them missing (reports/2026-10-22-parasite-eve-missing.md).
 */
class ScanSourceResolverTest {
    private val romScanner = mockk<RomScanner>(relaxed = true)
    private val romRootRepository = mockk<RomRootRepository>(relaxed = true)

    private val internalRoot = "content://com.android.externalstorage.documents/tree/primary%3ARoms"
    private val sdRoot = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AGames"
    private val internalPresent = setOf("/storage/emulated/0/Roms/psx/crash.bin")

    private val card = MemoryCard(
        platformId = "psx",
        displayName = "PlayStation Memory Card",
        romDirectory = "/storage/emulated/0/Roms/psx",
        supportedExtensions = listOf("cue", "bin"),
    )

    @Before
    fun setUp() {
        // childDocIdOf is a companion helper over DocumentsContract + Uri — untestable on the JVM.
        mockkObject(RomRootRepository.Companion)
        every { RomRootRepository.childDocIdOf(internalRoot, "psx") } returns "primary:Roms/psx"
        every { RomRootRepository.childDocIdOf(sdRoot, "psx") } returns "1A2B-3C4D:Games/psx"
        coEvery {
            romScanner.scanTree(internalRoot, any(), "psx", any(), any(), "primary:Roms/psx")
        } returns flowOf(ScanResult.Complete(emptyList(), 0, emptyList(), emptyList(), presentRomPaths = internalPresent))
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun resolver() = ScanSourceResolver(romScanner, romRootRepository, PlatformFolderHintResolver())

    private suspend fun presentSets(card: MemoryCard): List<Set<String>?> =
        resolver().sourcesFor(card).flatMap { source ->
            source(emptySet()).toList().filterIsInstance<ScanResult.Complete>().map { it.presentRomPaths }
        }

    @Test
    fun `an unreadable root leaves the card's survey untrusted`() = runTest {
        coEvery { romRootRepository.getAll() } returns listOf(internalRoot, sdRoot)
        coEvery { romScanner.listSubfolderNamesOrNull(internalRoot) } returns listOf("psx")
        coEvery { romScanner.listSubfolderNamesOrNull(sdRoot) } returns null

        val present = presentSets(card)

        assertTrue("the readable root is still scanned", internalPresent in present)
        assertTrue("an unreadable root contributes a null present-set", null in present)
    }

    @Test
    fun `an unreadable root is not replaced by the legacy raw directory`() = runTest {
        coEvery { romRootRepository.getAll() } returns listOf(sdRoot)
        coEvery { romScanner.listSubfolderNamesOrNull(sdRoot) } returns null

        val present = presentSets(card)

        assertEquals(listOf<Set<String>?>(null), present)
        coVerify(exactly = 0) { romScanner.scanDirectory(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `readable roots are scanned as before`() = runTest {
        coEvery {
            romScanner.scanTree(sdRoot, any(), "psx", any(), any(), "1A2B-3C4D:Games/psx")
        } returns flowOf(ScanResult.Complete(emptyList(), 0, emptyList(), emptyList(), presentRomPaths = emptySet()))
        coEvery { romRootRepository.getAll() } returns listOf(internalRoot, sdRoot)
        coEvery { romScanner.listSubfolderNamesOrNull(internalRoot) } returns listOf("psx")
        coEvery { romScanner.listSubfolderNamesOrNull(sdRoot) } returns listOf("psx", "gba")

        val present = presentSets(card)

        assertEquals(listOf(internalPresent, emptySet<String>()), present)
    }

    @Test
    fun `an unreadable root tells the scan why nothing was marked missing`() = runTest {
        coEvery { romRootRepository.getAll() } returns listOf(sdRoot)
        coEvery { romScanner.listSubfolderNamesOrNull(sdRoot) } returns null

        val errors = resolver().sourcesFor(card).flatMap { it(emptySet()).toList() }
            .filterIsInstance<ScanResult.Error>()

        assertEquals(1, errors.size)
        assertTrue(errors.single().message.contains("could not be read"))
    }

    @Test
    fun `readable roots with no matching folder leave no unknown survey behind`() = runTest {
        coEvery { romRootRepository.getAll() } returns listOf(internalRoot)
        coEvery { romScanner.listSubfolderNamesOrNull(internalRoot) } returns listOf("gba")

        assertTrue(null !in presentSets(card))
    }

    @Test
    fun `a card with no ROM roots still falls back to its raw directory`() = runTest {
        coEvery { romRootRepository.getAll() } returns emptyList()

        resolver().sourcesFor(card).single()(emptySet())

        coVerify(exactly = 1) { romScanner.scanDirectory("/storage/emulated/0/Roms/psx", any(), "psx", any(), any()) }
    }
}
