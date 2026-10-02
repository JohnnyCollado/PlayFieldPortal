package com.playfieldportal.core.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.PFPDatabase
import com.playfieldportal.core.data.database.entity.VideoEntity
import com.playfieldportal.core.data.database.entity.VideoLibraryEntity
import com.playfieldportal.core.data.database.entity.VideoPlaylistEntity
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

/**
 * An imported video playlist is created and filled in one transaction: its rows hold the matched
 * ids in file order, and it sorts after the playlists that were already there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class VideoPlaylistDaoImportTest {

    private lateinit var db: PFPDatabase
    private lateinit var dao: VideoPlaylistDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PFPDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.videoPlaylistDao()
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
    fun `rows hold the given ids in order and the count matches`() = runTest {
        videos("a", "b", "c")

        val id = dao.insertWithVideos("Mix", listOf("c", "a", "b"), now = 5L)

        assertEquals(listOf("c", "a", "b"), dao.observeVideos(id).first().map { it.id })
        val row = dao.observeAllWithCounts().first().single()
        assertEquals(id, row.playlist.id)
        assertEquals("Mix", row.playlist.name)
        assertEquals(3, row.video_count)
    }

    @Test
    fun `the new playlist sorts after the existing ones`() = runTest {
        videos("a")
        dao.insert(VideoPlaylistEntity(name = "Old", createdAt = 1L, updatedAt = 1L, sortOrder = 4))

        val id = dao.insertWithVideos("New", listOf("a"), now = 5L)

        assertEquals(5, dao.getById(id)!!.sortOrder)
    }

    @Test
    fun `repeated ids are stored once in first-appearance order`() = runTest {
        videos("a", "b")

        val id = dao.insertWithVideos("Mix", listOf("b", "a", "b"), now = 5L)

        assertEquals(listOf("b", "a"), dao.observeVideos(id).first().map { it.id })
        assertEquals(1, dao.maxPosition(id))
        assertTrue(dao.observeAllWithCounts().first().single().video_count == 2)
    }
}
