package com.playfieldportal.feature.artwork.api

import android.net.Uri
import app.cash.turbine.test
import com.playfieldportal.core.data.repository.ArtworkFolderRepository
import com.playfieldportal.feature.artwork.portable.PortableArtworkLibrary
import com.playfieldportal.feature.artwork.store.InternalArtworkStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ArtworkFolderStatusTest {

    private val tree = "content://com.android.externalstorage.documents/tree/primary%3AArtwork"
    private val name = "/storage/emulated/0/Artwork"

    private val repo = mockk<ArtworkFolderRepository>(relaxed = true)
    private val library = mockk<PortableArtworkLibrary>()
    private val internal = mockk<InternalArtworkStore>()

    private fun status() = ArtworkFolderStatus(repo, library, internal)

    private fun linked(liveGrant: Boolean, rootReachable: Boolean) {
        coEvery { repo.getTreeUri() } returns tree
        coEvery { repo.hasLiveGrant() } returns liveGrant
        coEvery { library.isRootReachable(Uri.parse(tree)) } returns rootReachable
    }

    @Test
    fun `starts unknown`() {
        assertEquals(ArtworkFolderState.Unknown, status().state.value)
    }

    @Test
    fun `no uri is not linked with the internal footprint`() = runTest {
        coEvery { repo.getTreeUri() } returns null
        coEvery { internal.footprint() } returns (5 to 1024L)
        val s = status()
        assertEquals(ArtworkFolderState.NotLinked(5), s.refresh())
        assertEquals(ArtworkFolderState.NotLinked(5), s.state.value)
    }

    @Test
    fun `dead grant is unavailable`() = runTest {
        linked(liveGrant = false, rootReachable = true)
        assertEquals(ArtworkFolderState.Unavailable(tree, name), status().refresh())
    }

    @Test
    fun `live grant with a failing root probe is unavailable`() = runTest {
        linked(liveGrant = true, rootReachable = false)
        assertEquals(ArtworkFolderState.Unavailable(tree, name), status().refresh())
    }

    @Test
    fun `a throwing probe maps to unavailable`() = runTest {
        coEvery { repo.getTreeUri() } returns tree
        coEvery { repo.hasLiveGrant() } throws IllegalStateException("boom")
        assertEquals(ArtworkFolderState.Unavailable(tree, name), status().refresh())
    }

    @Test
    fun `everything ok is ready`() = runTest {
        linked(liveGrant = true, rootReachable = true)
        assertEquals(ArtworkFolderState.Ready(tree, name), status().refresh())
    }

    @Test
    fun `reportBlocked emits once`() = runTest {
        val s = status()
        s.folderNeeded.test {
            s.reportBlocked(FolderNeedTrigger.SCRAPE)
            assertEquals(FolderNeed(FolderNeedTrigger.SCRAPE), awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun `reaching ready clears the deferral`() = runTest {
        linked(liveGrant = true, rootReachable = true)
        coEvery { repo.isPromptDeferred() } returns true
        status().refresh()
        coVerify(exactly = 1) { repo.setPromptDeferred(false) }
    }

    @Test
    fun `not reaching ready leaves the deferral alone`() = runTest {
        linked(liveGrant = false, rootReachable = true)
        coEvery { repo.isPromptDeferred() } returns true
        status().refresh()
        coVerify(exactly = 0) { repo.setPromptDeferred(any()) }
    }
}
