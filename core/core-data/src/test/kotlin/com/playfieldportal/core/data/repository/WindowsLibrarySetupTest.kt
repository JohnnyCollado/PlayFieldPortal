package com.playfieldportal.core.data.repository

import android.content.Context
import com.playfieldportal.core.domain.model.MemoryCard
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Decision logic of [WindowsLibrarySetup] over a fake SAF surface: find-vs-create, read-only
 * degradation, idempotence, and the explicit-pick override
 * (docs/windows-library-refactor-plan.md section 2).
 */
@RunWith(RobolectricTestRunner::class)
class WindowsLibrarySetupTest {

    private val internalRoot = "content://com.android.externalstorage.documents/tree/primary%3ARoms"
    private val sdRoot = "content://com.android.externalstorage.documents/tree/408C-3861%3ARoms"

    private val romRoots = mockk<RomRootRepository>()
    private val memoryCards = mockk<MemoryCardRepository>(relaxed = true)

    private fun setup() = WindowsLibrarySetup(mockk<Context>(relaxed = true), romRoots, memoryCards)

    private fun card(
        treeUri: String? = null,
        romDirectory: String? = null,
        extensions: List<String> = emptyList(),
    ) = MemoryCard(
        platformId          = "windows",
        displayName         = "Windows Games",
        treeUri             = treeUri,
        romDirectory        = romDirectory,
        supportedExtensions = extensions,
        scanRecursively     = false,
    )

    /** Fake SAF: existing dirs keyed by "parentDocId/name" (lowercase); create appends or refuses. */
    private class FakeOps(
        existing: Map<String, String> = emptyMap(),
        private val writable: Boolean = true,
    ) : WindowsLibrarySetup.FolderOps {
        val dirs = existing.mapKeys { it.key.lowercase() }.toMutableMap()
        val created = mutableListOf<String>()

        override fun findChildDir(treeUri: String, parentDocId: String, name: String): String? =
            dirs["$parentDocId/$name".lowercase()]

        override fun createChildDir(treeUri: String, parentDocId: String, name: String): String? {
            if (!writable) return null
            val docId = "$parentDocId/$name"
            dirs["$parentDocId/$name".lowercase()] = docId
            created += docId
            return docId
        }
    }

    @Test
    fun `no rom root reports NoRomRoot and still creates the card`() = runTest {
        coEvery { memoryCards.getById("windows") } returns null
        coEvery { memoryCards.addCard(any(), any(), any(), any(), any(), any()) } returns card()
        coEvery { romRoots.getAll() } returns emptyList()

        assertEquals(WindowsSetupState.NoRomRoot, setup().ensure(FakeOps()))
        coVerify { memoryCards.addCard("windows", "Windows Games", null, null, emptyList(), false) }
    }

    @Test
    fun `existing windows folder under any root is found and assigned`() = runTest {
        coEvery { memoryCards.getById("windows") } returns card()
        coEvery { romRoots.getAll() } returns listOf(internalRoot, sdRoot)
        // Only the SD root has a windows folder (case differs); import already exists.
        val ops = FakeOps(
            existing = mapOf(
                "408C-3861:Roms/Windows" to "408C-3861:Roms/Windows",
                "408C-3861:Roms/Windows/import" to "408C-3861:Roms/Windows/import",
            ),
        )

        val state = setup().ensure(ops)

        assertEquals(WindowsSetupState.Ready("/storage/408C-3861/Roms/Windows"), state)
        coVerify { memoryCards.setRomDirectory("windows", "/storage/408C-3861/Roms/Windows") }
        assertTrue(ops.created.isEmpty(), "nothing to create when folder and import exist")
    }

    @Test
    fun `missing folder is created under the first writable root with its import drop-folder`() = runTest {
        coEvery { memoryCards.getById("windows") } returns card()
        coEvery { romRoots.getAll() } returns listOf(internalRoot)
        val ops = FakeOps()

        val state = setup().ensure(ops)

        assertEquals(WindowsSetupState.Ready("/storage/emulated/0/Roms/windows"), state)
        assertEquals(listOf("primary:Roms/windows", "primary:Roms/windows/import"), ops.created)
    }

    @Test
    fun `read-only roots degrade to FolderUnavailable`() = runTest {
        coEvery { memoryCards.getById("windows") } returns card()
        coEvery { romRoots.getAll() } returns listOf(internalRoot, sdRoot)

        assertEquals(WindowsSetupState.FolderUnavailable, setup().ensure(FakeOps(writable = false)))
        coVerify(exactly = 0) { memoryCards.setRomDirectory(any(), any()) }
    }

    @Test
    fun `re-running against an assigned directory changes nothing`() = runTest {
        coEvery { memoryCards.getById("windows") } returns
            card(romDirectory = "/storage/emulated/0/Roms/windows")
        coEvery { romRoots.getAll() } returns listOf(internalRoot)
        val ops = FakeOps(
            existing = mapOf(
                "primary:Roms/windows" to "primary:Roms/windows",
                "primary:Roms/windows/import" to "primary:Roms/windows/import",
            ),
        )

        val state = setup().ensure(ops)

        assertEquals(WindowsSetupState.Ready("/storage/emulated/0/Roms/windows"), state)
        coVerify(exactly = 0) { memoryCards.setRomDirectory(any(), any()) }
        assertTrue(ops.created.isEmpty())
    }

    @Test
    fun `an explicitly picked folder is never overridden`() = runTest {
        val picked = "content://com.android.externalstorage.documents/tree/primary%3AMyGames"
        coEvery { memoryCards.getById("windows") } returns
            card(treeUri = picked, romDirectory = "/storage/emulated/0/MyGames")
        val ops = FakeOps()

        val state = setup().ensure(ops)

        assertEquals(WindowsSetupState.Ready("/storage/emulated/0/MyGames"), state)
        coVerify(exactly = 0) { romRoots.getAll() }
        coVerify(exactly = 0) { memoryCards.setRomDirectory(any(), any()) }
        // The drop-folder is still kept alive inside the picked folder.
        assertEquals(listOf("primary:MyGames/import"), ops.created)
    }

    @Test
    fun `stray extensions are cleared — windows scanning is extension-free`() = runTest {
        coEvery { memoryCards.getById("windows") } returns card(extensions = listOf("exe", "lnk"))
        coEvery { memoryCards.setExtensions(any(), any()) } just Runs
        coEvery { romRoots.getAll() } returns emptyList()

        setup().ensure(FakeOps())

        coVerify { memoryCards.setExtensions("windows", emptyList()) }
    }

    @Test
    fun `importFolders resolves only existing import drop-folders under the windows surfaces`() = runTest {
        coEvery { memoryCards.getById("windows") } returns card()
        coEvery { romRoots.getAll() } returns listOf(internalRoot, sdRoot)
        // Both roots have windows folders; only the internal one has import/ yet.
        val ops = FakeOps(
            existing = mapOf(
                "primary:Roms/windows" to "primary:Roms/windows",
                "primary:Roms/windows/import" to "primary:Roms/windows/import",
                "408C-3861:Roms/windows" to "408C-3861:Roms/windows",
            ),
        )

        assertEquals(
            listOf(internalRoot to "primary:Roms/windows/import"),
            setup().importFolders(ops),
        )
    }

    @Test
    fun `windowsFolders lists every root's windows child, or the picked folder alone`() = runTest {
        coEvery { memoryCards.getById("windows") } returns card()
        coEvery { romRoots.getAll() } returns listOf(internalRoot, sdRoot)
        val ops = FakeOps(
            existing = mapOf(
                "primary:Roms/windows" to "primary:Roms/windows",
                "408C-3861:Roms/windows" to "408C-3861:Roms/windows",
            ),
        )

        assertEquals(
            listOf(internalRoot to "primary:Roms/windows", sdRoot to "408C-3861:Roms/windows"),
            setup().windowsFolders(ops),
        )

        val picked = "content://com.android.externalstorage.documents/tree/primary%3AMyGames"
        coEvery { memoryCards.getById("windows") } returns card(treeUri = picked)
        assertEquals(listOf(picked to "primary:MyGames"), setup().windowsFolders(ops))
    }
}
