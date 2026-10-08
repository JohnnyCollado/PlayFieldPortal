package com.playfieldportal.feature.artwork.store

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.playfieldportal.core.data.database.dao.ArtworkRecordDao
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.repository.ArtworkFolderRepository
import com.playfieldportal.feature.artwork.api.ArtworkFolderState
import com.playfieldportal.feature.artwork.api.ArtworkFolderStatus
import com.playfieldportal.feature.artwork.api.ArtworkImageCache
import com.playfieldportal.feature.artwork.api.FolderNeed
import com.playfieldportal.feature.artwork.api.FolderNeedTrigger
import com.playfieldportal.feature.artwork.portable.ArtworkIdentityRecorder
import com.playfieldportal.feature.artwork.portable.PortableArtworkLibrary
import io.ktor.client.HttpClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** AD-3: with no ready folder every write returns null, writes nothing internally, and reports. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RoutingArtworkStorePausedTest {

    private val tree = "content://com.android.externalstorage.documents/tree/primary%3AArtwork"
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val internal = mockk<InternalArtworkStore>()
    private val library = mockk<PortableArtworkLibrary>()
    private val repo = mockk<ArtworkFolderRepository>()
    private val gameDao = mockk<GameDao>()
    private val recordDao = mockk<ArtworkRecordDao>()
    private val status = ArtworkFolderStatus(repo, library, internal)

    private val store = RoutingArtworkStore(
        context = context,
        internal = internal,
        library = library,
        folderRepository = repo,
        gameDao = gameDao,
        artworkRecordDao = recordDao,
        httpClient = mockk<HttpClient>(),
        imageCache = mockk<ArtworkImageCache>(relaxed = true),
        identityRecorder = mockk<ArtworkIdentityRecorder>(relaxed = true),
        folderStatus = status,
    )

    private fun tempFile(): File = File.createTempFile("paused", ".png", context.cacheDir)

    private suspend fun notLinked() {
        coEvery { repo.getTreeUri() } returns null
        coEvery { internal.footprint() } returns (0 to 0L)
        assertTrue(status.refresh() is ArtworkFolderState.NotLinked)
    }

    private suspend fun unavailable() {
        coEvery { repo.getTreeUri() } returns tree
        coEvery { repo.hasLiveGrant() } returns false
        assertTrue(status.refresh() is ArtworkFolderState.Unavailable)
    }

    private suspend fun ready() {
        coEvery { repo.isPromptDeferred() } returns false
        coEvery { repo.getTreeUri() } returns tree
        coEvery { repo.hasLiveGrant() } returns true
        coEvery { library.isRootReachable(Uri.parse(tree)) } returns true
        assertTrue(status.refresh() is ArtworkFolderState.Ready)
    }

    private fun verifyNoInternalWrite() {
        coVerify(exactly = 0) { internal.saveFromUrl(any(), any(), any(), any()) }
        coVerify(exactly = 0) { internal.saveVersionedFromUrl(any(), any(), any()) }
        coVerify(exactly = 0) { internal.saveVersionedFromUri(any(), any(), any()) }
        coVerify(exactly = 0) { internal.saveFromFile(any(), any(), any(), any()) }
    }

    /** Runs [write] and asserts it returned null and emitted exactly [expected] on folderNeeded. */
    private suspend fun assertPaused(expected: FolderNeedTrigger, write: suspend () -> String?) {
        status.folderNeeded.test {
            assertNull(write())
            assertEquals(FolderNeed(expected), awaitItem())
            expectNoEvents()
        }
        verifyNoInternalWrite()
    }

    @Test
    fun `a write before the first probe probes instead of pausing`() = runTest {
        // A scrape started by the launch-time rescan can beat PFPApplication's refresh(); the
        // Unknown state must not read as "no folder" and raise the prompt.
        coEvery { repo.isPromptDeferred() } returns false
        coEvery { repo.getTreeUri() } returns tree
        coEvery { repo.hasLiveGrant() } returns true
        coEvery { library.isRootReachable(Uri.parse(tree)) } returns true
        coEvery { gameDao.getById(1L) } returns null
        assertEquals(ArtworkFolderState.Unknown, status.state.value)
        status.folderNeeded.test {
            assertNull(store.saveFromUrl(1L, ArtworkKind.HERO, "https://x/y.png", 0))
            expectNoEvents()
        }
        assertTrue(status.state.value is ArtworkFolderState.Ready)
        coVerify { gameDao.getById(1L) }
        verifyNoInternalWrite()
    }

    @Test
    fun `scrape writes pause with a SCRAPE report when not linked`() = runTest {
        notLinked()
        assertPaused(FolderNeedTrigger.SCRAPE) { store.saveFromUrl(1L, ArtworkKind.HERO, "https://x/y.png", 0) }
        val file = tempFile()
        assertPaused(FolderNeedTrigger.SCRAPE) { store.saveFromFile(1L, ArtworkKind.HERO, file, 0) }
        assertFalse("the temp file is consumed", file.exists())
    }

    @Test
    fun `scrape writes pause with a SCRAPE report when unavailable`() = runTest {
        unavailable()
        assertPaused(FolderNeedTrigger.SCRAPE) { store.saveFromUrl(1L, ArtworkKind.HERO, "https://x/y.png", 0) }
    }

    @Test
    fun `user writes pause with a PICK report`() = runTest {
        notLinked()
        assertPaused(FolderNeedTrigger.PICK) {
            store.saveVersionedFromUri(1L, ArtworkKind.HERO, Uri.parse("content://picked/1"))
        }
        assertPaused(FolderNeedTrigger.PICK) {
            store.studioApplyFromUrl(1L, ArtworkKind.HERO, "https://x/y.png", provider = null)
        }
        val file = tempFile()
        assertPaused(FolderNeedTrigger.PICK) { store.studioAppendFromFile(1L, ArtworkKind.HERO, file, provider = null) }
        assertFalse("the temp file is consumed", file.exists())
    }

    @Test
    fun `user writes pause the same way when unavailable`() = runTest {
        unavailable()
        assertPaused(FolderNeedTrigger.PICK) {
            store.saveVersionedFromUrl(1L, ArtworkKind.HERO, "https://x/y.png")
        }
        val file = tempFile()
        assertPaused(FolderNeedTrigger.PICK) {
            store.studioApplyFromFile(1L, ArtworkKind.HERO, file, provider = null, originUrl = null)
        }
        assertFalse("the temp file is consumed", file.exists())
    }

    @Test
    fun `crop writes restore and reset report when paused`() = runTest {
        notLinked()
        coEvery { recordDao.getAt(any(), any(), any()) } returns null
        val baked = tempFile()
        assertPaused(FolderNeedTrigger.PICK) { store.saveCropBaked(1L, ArtworkKind.HERO, baked, "0,0,1,1") }
        assertFalse(baked.exists())
        val draw = tempFile()
        assertPaused(FolderNeedTrigger.PICK) { store.saveCropAtDraw(1L, ArtworkKind.HERO, draw, "0,0,1,1") }
        assertFalse(draw.exists())
        assertPaused(FolderNeedTrigger.PICK) { store.restorePrevious(1L, ArtworkKind.HERO) }
    }

    @Test
    fun `reset to scraped default reports SCRAPE when paused`() = runTest {
        notLinked()
        coEvery { recordDao.getAt(1L, ArtworkKind.HERO.name, 0) } returns
            mockk<com.playfieldportal.core.data.database.entity.ArtworkRecordEntity>(relaxed = true) { every { originUrl } returns "https://x/y.png" }
        assertPaused(FolderNeedTrigger.SCRAPE) { store.resetToScrapedDefault(1L, ArtworkKind.HERO) }
    }

    @Test
    fun `saveTempPortable with no folder returns null and never reports`() = runTest {
        notLinked()
        val file = tempFile()
        status.folderNeeded.test {
            assertNull(store.saveTempPortable(1L, ArtworkKind.HERO, file, "user", true))
            expectNoEvents()
        }
        assertFalse(file.exists())
    }

    @Test
    fun `find and findAll still read internal refs while paused`() = runTest {
        notLinked()
        coEvery { internal.find(1L, ArtworkKind.HERO, 0) } returns "file:///internal/hero.png"
        coEvery { internal.findAll(1L, ArtworkKind.HERO) } returns listOf("file:///internal/hero.png")
        coEvery { recordDao.getAt(any(), any(), any()) } returns null
        coEvery { recordDao.findAll(any(), any()) } returns emptyList()
        assertEquals("file:///internal/hero.png", store.find(1L, ArtworkKind.HERO, 0))
        assertEquals(listOf("file:///internal/hero.png"), store.findAll(1L, ArtworkKind.HERO))
    }

    @Test
    fun `clearArtwork while paused still deletes the internal kind`() = runTest {
        notLinked()
        coEvery { internal.deleteKind(1L, ArtworkKind.HERO) } returns Unit
        assertTrue(store.clearArtwork(1L, ArtworkKind.HERO))
        coVerify(exactly = 1) { internal.deleteKind(1L, ArtworkKind.HERO) }
    }

    @Test
    fun `a failed portable write that leaves the folder unreachable reports`() = runTest {
        ready()
        stubGameAndSlot()
        coEvery { library.saveFromFile(any(), any(), any(), any(), any()) } returns null
        // The folder disappears mid-run: the probe that follows the failed write now fails.
        coEvery { library.isRootReachable(Uri.parse(tree)) } returns false
        val file = tempFile()
        assertPaused(FolderNeedTrigger.SCRAPE) { store.saveFromFile(1L, ArtworkKind.HERO, file, 0) }
        assertTrue(status.state.value is ArtworkFolderState.Unavailable)
    }

    @Test
    fun `a failed portable write with the folder still ready does not report`() = runTest {
        ready()
        stubGameAndSlot()
        coEvery { library.saveFromFile(any(), any(), any(), any(), any()) } returns null
        status.folderNeeded.test {
            assertNull(store.saveFromFile(1L, ArtworkKind.HERO, tempFile(), 0))
            expectNoEvents()
        }
        assertTrue(status.state.value is ArtworkFolderState.Ready)
    }

    @Test
    fun `a dead grant while ready refreshes and reports`() = runTest {
        ready()
        // The grant dies between the last probe and this write.
        coEvery { repo.hasLiveGrant() } returns false
        stubGameAndSlot()
        assertPaused(FolderNeedTrigger.SCRAPE) { store.saveFromFile(1L, ArtworkKind.HERO, tempFile(), 0) }
        assertTrue(status.state.value is ArtworkFolderState.Unavailable)
    }

    private fun stubGameAndSlot() {
        val game = mockk<GameEntity>(relaxed = true) {
            every { id } returns 1L
            every { romPath } returns "/roms/Game.sfc"
        }
        coEvery { gameDao.getById(1L) } returns game
        coEvery { recordDao.getAt(any(), any(), any()) } returns null
        coEvery { recordDao.findNameCollisions(any(), any(), any(), any()) } returns emptyList()
    }
}
