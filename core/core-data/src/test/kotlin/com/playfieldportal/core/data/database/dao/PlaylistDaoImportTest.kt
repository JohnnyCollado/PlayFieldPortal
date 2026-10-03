package com.playfieldportal.core.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.PFPDatabase
import com.playfieldportal.core.data.database.entity.MusicFolderEntity
import com.playfieldportal.core.data.database.entity.MusicTrackEntity
import com.playfieldportal.core.data.database.entity.PlaylistEntity
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
 * An imported playlist is created and filled in one transaction: its rows hold the matched ids in
 * file order, and it sorts after the playlists that were already there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaylistDaoImportTest {

    private lateinit var db: PFPDatabase
    private lateinit var dao: PlaylistDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PFPDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.playlistDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun tracks(vararg ids: String) {
        db.musicFolderDao().upsert(
            MusicFolderEntity(id = "f", displayName = "f", treeUri = "content://tree/f", createdAt = 0L, updatedAt = 0L),
        )
        db.musicTrackDao().insertAll(
            ids.map { MusicTrackEntity(id = it, folderId = "f", uri = "content://$it", displayName = "$it.mp3") },
        )
    }

    @Test
    fun `rows hold the given ids in order and the count matches`() = runTest {
        tracks("a", "b", "c")

        val id = dao.insertWithTracks("Mix", listOf("c", "a", "b"), now = 5L)

        assertEquals(listOf("c", "a", "b"), dao.observeTracks(id).first().map { it.id })
        val row = dao.observeAllWithCounts().first().single()
        assertEquals(id, row.playlist.id)
        assertEquals("Mix", row.playlist.name)
        assertEquals(3, row.track_count)
    }

    @Test
    fun `the new playlist sorts after the existing ones`() = runTest {
        tracks("a")
        dao.insert(PlaylistEntity(name = "Old", createdAt = 1L, updatedAt = 1L, sortOrder = 4))

        val id = dao.insertWithTracks("New", listOf("a"), now = 5L)

        assertEquals(5, dao.getById(id)!!.sortOrder)
    }

    @Test
    fun `repeated ids are stored once in first-appearance order`() = runTest {
        tracks("a", "b")

        val id = dao.insertWithTracks("Mix", listOf("b", "a", "b"), now = 5L)

        assertEquals(listOf("b", "a"), dao.observeTracks(id).first().map { it.id })
        assertEquals(1, dao.maxPosition(id))
        assertTrue(dao.observeAllWithCounts().first().single().track_count == 2)
    }
}
