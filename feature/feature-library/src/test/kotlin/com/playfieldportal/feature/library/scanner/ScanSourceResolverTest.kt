package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.data.platform.PlatformFolderHintResolver
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.core.domain.model.MemoryCard
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A ROM root that cannot be listed must never let a scan mark that root's games Missing.
 *
 * Survey L1 (docs/audits/codebase-survey-2026-10-22.md): root-managed cards listed each root's
 * subfolders with the empty-on-failure SAF query, so an SD card still mounting at boot was simply
 * left out of the sources. The scan then looked complete and `LibraryReconciler` marked every game
 * on that card missing. An unreadable root now adds a source whose survey is "unknown"
 * (`presentRomPaths = null`), which `LibraryScanner` already treats as "skip removals".
 */
class ScanSourceResolverTest {

    private val internalRoot = "content://com.android.externalstorage.documents/tree/primary%3ARoms"
    private val sdRoot = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3ARoms"

    private val romScanner = mockk<RomScanner>()
    private val roots = mockk<RomRootRepository>()
    private val resolver = ScanSourceResolver(romScanner, roots, PlatformFolderHintResolver())

    private fun psxCard() = MemoryCard(platformId = "psx", displayName = "PlayStation")

    private suspend fun surveys(card: MemoryCard): List<Set<String>?> =
        resolver.sourcesFor(card).flatMap { source -> source(emptySet()).toList() }
            .filterIsInstance<ScanResult.Complete>()
            .map { it.presentRomPaths }

    @Test
    fun `an unreadable root makes the survey unknown so nothing is marked missing`() = runTest {
        coEvery { roots.getAll() } returns listOf(internalRoot, sdRoot)
        coEvery { romScanner.listSubfolderNamesOrNull(internalRoot) } returns listOf("psx")
        coEvery { romScanner.listSubfolderNamesOrNull(sdRoot) } returns null
        coEvery { romScanner.scanTree(any(), any(), any(), any(), any(), any()) } returns
            kotlinx.coroutines.flow.flowOf(
                ScanResult.Complete(emptyList(), 0, emptyList(), emptyList(), presentRomPaths = setOf("/a.bin")),
            )

        assertTrue("an unknown survey must be reported", null in surveys(psxCard()))
    }

    @Test
    fun `the only root being unreadable still blocks removals`() = runTest {
        coEvery { roots.getAll() } returns listOf(sdRoot)
        coEvery { romScanner.listSubfolderNamesOrNull(sdRoot) } returns null

        val result = surveys(psxCard())

        assertTrue("an unreadable root must produce a source, not an empty list", result.isNotEmpty())
        assertTrue(result.all { it == null })
    }

    @Test
    fun `readable roots with no matching folder leave no unknown survey behind`() = runTest {
        coEvery { roots.getAll() } returns listOf(internalRoot)
        coEvery { romScanner.listSubfolderNamesOrNull(internalRoot) } returns listOf("gba")

        assertTrue(null !in surveys(psxCard()))
    }
}
