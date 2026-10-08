package com.playfieldportal.feature.artwork.api

import android.content.Context
import android.net.Uri
import com.playfieldportal.core.data.database.dao.ArtworkImportReportDao
import com.playfieldportal.core.data.database.dao.ArtworkOrphanFileDao
import com.playfieldportal.core.data.database.dao.ArtworkRecordDao
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.repository.ArtworkFolderRepository
import com.playfieldportal.feature.artwork.importer.ArtworkImportPlanner
import com.playfieldportal.feature.artwork.migrate.InternalArtworkMigrationWorker
import com.playfieldportal.feature.artwork.portable.ArtworkIdentityRecorder
import com.playfieldportal.feature.artwork.portable.ArtworkLibraryManifest
import com.playfieldportal.feature.artwork.portable.PortableArtworkLibrary
import com.playfieldportal.feature.artwork.store.ArtworkStore
import com.playfieldportal.feature.artwork.store.InternalArtworkStore
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ArtworkImportManagerLinkTest {

    private val treeString = "content://com.android.externalstorage.documents/tree/primary%3AArtwork"
    private val tree: Uri = Uri.parse(treeString)
    private val otherTreeString = "content://com.android.externalstorage.documents/tree/primary%3AOther"

    private val context = mockk<Context>(relaxed = true)
    private val folderRepository = mockk<ArtworkFolderRepository>(relaxed = true)
    private val library = mockk<PortableArtworkLibrary>(relaxed = true)
    private val gameDao = mockk<GameDao>(relaxed = true)
    private val internalStore = mockk<InternalArtworkStore>()
    private val folderState = MutableStateFlow<ArtworkFolderState>(readyFolder)
    private val status = folderStatusOf(folderState)

    private fun manifest(uuid: String) = ArtworkLibraryManifest(libraryUuid = uuid, createdAt = 1L)

    private fun manager() = ArtworkImportManager(
        context = context,
        folderRepository = folderRepository,
        library = library,
        planner = mockk<ArtworkImportPlanner>(relaxed = true),
        reportDao = mockk<ArtworkImportReportDao>(relaxed = true),
        gameDao = gameDao,
        artworkRecordDao = mockk<ArtworkRecordDao>(relaxed = true),
        artworkStore = mockk<ArtworkStore>(relaxed = true),
        internalStore = internalStore,
        identityRecorder = mockk<ArtworkIdentityRecorder>(relaxed = true),
        orphanFileDao = mockk<ArtworkOrphanFileDao>(relaxed = true),
        status = status,
    )

    @Before
    fun setUp() {
        mockkObject(InternalArtworkMigrationWorker.Companion)
        mockkObject(ArtworkRelinkWorker.Companion)
        every { InternalArtworkMigrationWorker.enqueue(any()) } returns UUID.randomUUID()
        every { ArtworkRelinkWorker.enqueue(any(), any()) } returns UUID.randomUUID()
        coEvery { internalStore.footprint() } returns (0 to 0L)
        coEvery { folderRepository.getLibraryUuid() } returns null
        coEvery { folderRepository.getTreeUri() } returns treeString
        coEvery { library.readManifest(tree) } returns null
        coEvery { library.ensureLibrary(tree, any()) } returns manifest("created")
    }

    @After
    fun tearDown() {
        unmockkAll()
        clearAllMocks()
    }

    @Test
    fun `stored uuid A with manifest B is foreign and writes nothing`() = runTest {
        coEvery { folderRepository.getLibraryUuid() } returns "A"
        coEvery { library.readManifest(tree) } returns manifest("B")
        coEvery { library.countLibraryFiles(tree) } returns 42

        val result = manager().linkFolder(tree)

        assertTrue(result is ArtworkImportManager.LinkResult.ForeignLibrary)
        coVerify(exactly = 0) { folderRepository.setTreeUri(any()) }
        coVerify(exactly = 0) { folderRepository.setLibraryUuid(any()) }
        verify { status.setPendingForeignLibrary(ForeignLibrary(treeString, "/storage/emulated/0/Artwork", 42)) }
        coVerify(exactly = 0) { status.refresh() }
    }

    @Test
    fun `stored uuid A with manifest A links as an existing library`() = runTest {
        coEvery { folderRepository.getLibraryUuid() } returns "A"
        coEvery { library.readManifest(tree) } returns manifest("A")

        val result = manager().linkFolder(tree)

        assertEquals(ArtworkImportManager.LinkResult.Linked(existingLibrary = true), result)
        coVerify { folderRepository.setTreeUri(treeString) }
        coVerify { folderRepository.setLibraryUuid("A") }
    }

    @Test
    fun `no stored uuid with manifest B adopts it and stores the uuid`() = runTest {
        coEvery { library.readManifest(tree) } returns manifest("B")

        val result = manager().linkFolder(tree)

        assertEquals(ArtworkImportManager.LinkResult.Linked(existingLibrary = true), result)
        coVerify { folderRepository.setLibraryUuid("B") }
    }

    @Test
    fun `no manifest creates a library`() = runTest {
        val result = manager().linkFolder(tree)

        assertEquals(ArtworkImportManager.LinkResult.Linked(existingLibrary = false), result)
        coVerify { folderRepository.setLibraryUuid("created") }
    }

    @Test
    fun `an unwritable folder fails`() = runTest {
        coEvery { library.ensureLibrary(tree, any()) } returns null

        assertEquals(ArtworkImportManager.LinkResult.Failed, manager().linkFolder(tree))
        coVerify(exactly = 0) { folderRepository.setTreeUri(any()) }
    }

    @Test
    fun `a successful link refreshes the folder status`() = runTest {
        manager().linkFolder(tree)

        coVerify { status.refresh() }
        verify { status.setPendingForeignLibrary(null) }
    }

    @Test
    fun `a footprint above zero enqueues the migration`() = runTest {
        coEvery { internalStore.footprint() } returns (7 to 2048L)

        manager().linkFolder(tree)

        verify(exactly = 1) { InternalArtworkMigrationWorker.enqueue(any()) }
    }

    @Test
    fun `an empty footprint enqueues no migration`() = runTest {
        manager().linkFolder(tree)

        verify(exactly = 0) { InternalArtworkMigrationWorker.enqueue(any()) }
    }

    @Test
    fun `adopt writes the tree and uuid, clears pending, relinks and migrates`() = runTest {
        val pending = ForeignLibrary(treeString, "Artwork", 42)
        every { status.pendingForeignLibrary } returns MutableStateFlow(pending)
        coEvery { library.readManifest(tree) } returns manifest("B")
        coEvery { internalStore.footprint() } returns (3 to 10L)

        val result = manager().adoptPendingLibrary()

        assertEquals(ArtworkImportManager.LinkResult.Linked(existingLibrary = true), result)
        coVerify { folderRepository.setTreeUri(treeString) }
        coVerify { folderRepository.setLibraryUuid("B") }
        verify { status.setPendingForeignLibrary(null) }
        coVerify { status.refresh() }
        verify { ArtworkRelinkWorker.enqueue(any(), any()) }
        verify { InternalArtworkMigrationWorker.enqueue(any()) }
    }

    @Test
    fun `adopt with nothing pending fails`() = runTest {
        every { status.pendingForeignLibrary } returns MutableStateFlow(null)

        assertEquals(ArtworkImportManager.LinkResult.Failed, manager().adoptPendingLibrary())
        coVerify(exactly = 0) { folderRepository.setTreeUri(any()) }
    }

    @Test
    fun `decline releases the grant when it is not the stored tree`() = runTest {
        val pending = ForeignLibrary(otherTreeString, "Other", 5)
        every { status.pendingForeignLibrary } returns MutableStateFlow(pending)

        manager().declinePendingLibrary()

        verify { context.contentResolver.releasePersistableUriPermission(Uri.parse(otherTreeString), any()) }
        verify { status.setPendingForeignLibrary(null) }
    }

    @Test
    fun `decline keeps the grant when it is the stored tree`() = runTest {
        val pending = ForeignLibrary(treeString, "Artwork", 5)
        every { status.pendingForeignLibrary } returns MutableStateFlow(pending)

        manager().declinePendingLibrary()

        verify(exactly = 0) { context.contentResolver.releasePersistableUriPermission(any(), any()) }
        verify { status.setPendingForeignLibrary(null) }
    }

    @Test
    fun `relink returns null when the folder is not ready`() = runTest {
        folderState.value = unavailableFolder

        assertNull(manager().relinkLibrary())

        coVerify(exactly = 0) { gameDao.getAll() }
    }
}
