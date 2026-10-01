package com.playfieldportal.core.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.PFPDatabase
import com.playfieldportal.core.data.database.entity.VideoEntity
import com.playfieldportal.core.data.database.entity.VideoLibraryEntity
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
 * A library rescan must not undo what the user did while it ran.
 *
 * The scanner reads each video's resume point, favourite, title and custom thumbnail when it
 * STARTS, and a scan of a large library takes minutes. Writing that snapshot back overwrote
 * anything watched, favourited or renamed in between — so those columns are taken from the row
 * as it stands when the scan's result is written.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class VideoDaoReplaceTest {

    private lateinit var db: PFPDatabase
    private lateinit var dao: VideoDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PFPDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.videoDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun library(id: String = "lib") {
        db.videoLibraryDao().upsert(
            VideoLibraryEntity(id = id, displayName = id, treeUri = "content://tree/$id", createdAt = 0L, updatedAt = 0L),
        )
    }

    private fun video(id: String, displayName: String = "$id.mkv", durationMs: Long? = null) = VideoEntity(
        id = id,
        libraryId = "lib",
        uri = "content://tree/lib/$id.mkv",
        displayName = displayName,
        durationMs = durationMs,
    )

    @Test
    fun `what the user changed during the scan survives it`() = runTest {
        library()
        dao.insertAll(listOf(video("a")))
        // The scan's snapshot, taken before the user did anything.
        val scanned = dao.getForLibrary("lib").map { it.copy(durationMs = 1_000L) }

        // Meanwhile: watched to 42s, favourited, renamed, given a custom thumbnail.
        dao.updateResumePosition("a", positionMs = 42_000L, watchedAt = 7L)
        dao.setFavorite("a", true)
        dao.setTitle("a", "My Title")
        dao.setCustomThumbnail("a", "content://thumb/a.png")

        dao.replaceForLibrary("lib", scanned)

        val row = dao.getById("a")!!
        assertEquals(42_000L, row.resumePositionMs)
        assertEquals(7L, row.lastWatchedAt)
        assertTrue(row.isFavorite)
        assertEquals("My Title", row.title)
        assertEquals("content://thumb/a.png", row.customThumbnailUri)
        // And the scan's own findings still land.
        assertEquals(1_000L, row.durationMs)
    }

    @Test
    fun `new files are added and vanished files are dropped`() = runTest {
        library()
        dao.insertAll(listOf(video("gone"), video("kept")))

        dao.replaceForLibrary("lib", listOf(video("kept"), video("new")))

        assertEquals(setOf("kept", "new"), dao.getForLibrary("lib").map { it.id }.toSet())
    }
}
