package com.playfieldportal.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.PFPDatabase
import com.playfieldportal.core.data.database.entity.VideoEntity
import com.playfieldportal.core.data.database.entity.VideoLibraryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFailsWith

/** `importPlaylist` over a real in-memory Room: one filled playlist, or nothing at all. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class VideoRepositoryImportTest {

    private lateinit var db: PFPDatabase
    private lateinit var repo: VideoRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PFPDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = VideoRepositoryImpl(
            ApplicationProvider.getApplicationContext(),
            db.videoLibraryDao(),
            db.videoDao(),
            db.videoPlaylistDao(),
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun videos(vararg ids: String) {
        db.videoLibraryDao().upsert(
            VideoLibraryEntity(id = "lib", displayName = "lib", treeUri = "content://tree/lib", createdAt = 0L, updatedAt = 0L),
        )
        db.videoDao().insertAll(
            ids.map { VideoEntity(id = it, libraryId = "lib", uri = "content://tree/lib/$it.mkv", displayName = "$it.mkv") },
        )
    }

    @Test
    fun `importPlaylist creates one filled playlist in the given order`() = runTest {
        videos("v1", "v2")

        val id = repo.importPlaylist("Road Trip", listOf("v2", "v1"))

        val playlist = repo.observePlaylists().first().single()
        assertEquals(id, playlist.id)
        assertEquals("Road Trip", playlist.name)
        assertEquals(listOf("v2", "v1"), repo.observePlaylistVideos(id).first().map { it.id })
    }

    @Test
    fun `importPlaylist with no ids throws and creates nothing`() = runTest {
        assertFailsWith<IllegalArgumentException> { repo.importPlaylist("Empty", emptyList()) }

        assertTrue(repo.observePlaylists().first().isEmpty())
    }
}
