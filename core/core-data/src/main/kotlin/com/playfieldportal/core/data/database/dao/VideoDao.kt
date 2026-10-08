package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.playfieldportal.core.data.database.entity.VideoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {

    @Query(
        """
        SELECT * FROM videos
        ORDER BY COALESCE(title, display_name) COLLATE NOCASE ASC
        """
    )
    fun observeAll(): Flow<List<VideoEntity>>

    @Query(
        """
        SELECT * FROM videos
        WHERE library_id = :libraryId
        ORDER BY COALESCE(title, display_name) COLLATE NOCASE ASC
        """
    )
    fun observeByLibrary(libraryId: String): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE library_id = :libraryId")
    suspend fun getForLibrary(libraryId: String): List<VideoEntity>

    @Query(
        """
        SELECT * FROM videos
        WHERE is_favorite = 1
        ORDER BY COALESCE(title, display_name) COLLATE NOCASE ASC
        """
    )
    fun observeFavorites(): Flow<List<VideoEntity>>

    // Most-recently-watched first; only videos that have actually been played (have a timestamp).
    @Query(
        """
        SELECT * FROM videos
        WHERE last_watched_at IS NOT NULL
        ORDER BY last_watched_at DESC
        LIMIT :limit
        """
    )
    fun observeRecentlyWatched(limit: Int): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE id = :id")
    suspend fun getById(id: String): VideoEntity?

    // How many rows still reference a cached thumbnail (generated or custom) — 0 means its file
    // can be deleted.
    @Query("SELECT COUNT(*) FROM videos WHERE thumbnail_uri = :uri OR custom_thumbnail_uri = :uri")
    suspend fun countReferencingThumbnail(uri: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(videos: List<VideoEntity>)

    @Query("DELETE FROM videos WHERE library_id = :libraryId")
    suspend fun deleteForLibrary(libraryId: String)

    @Query("DELETE FROM videos WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE videos SET resume_position_ms = :positionMs, last_watched_at = :watchedAt WHERE id = :id")
    suspend fun updateResumePosition(id: String, positionMs: Long, watchedAt: Long)

    @Query("UPDATE videos SET resume_position_ms = 0 WHERE id = :id")
    suspend fun clearResumePosition(id: String)

    @Query("UPDATE videos SET title = :title WHERE id = :id")
    suspend fun setTitle(id: String, title: String?)

    @Query("UPDATE videos SET custom_thumbnail_uri = :uri WHERE id = :id")
    suspend fun setCustomThumbnail(id: String, uri: String?)

    @Query("UPDATE videos SET is_favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    // Replaces a single library's videos atomically; other libraries are never touched.
    //
    // What the user owns — resume point, favourite, title, custom thumbnail — is taken from the row
    // as it stands NOW, not from the scan: the scanner copied those when it started, and anything
    // watched, favourited or renamed during a minutes-long scan would otherwise be written over.
    @Transaction
    suspend fun replaceForLibrary(libraryId: String, videos: List<VideoEntity>) {
        val live = getForLibrary(libraryId).associateBy { it.id }
        deleteForLibrary(libraryId)
        if (videos.isEmpty()) return
        insertAll(
            videos.map { scanned ->
                val current = live[scanned.id] ?: return@map scanned
                scanned.copy(
                    title = current.title,
                    customThumbnailUri = current.customThumbnailUri,
                    resumePositionMs = current.resumePositionMs,
                    lastWatchedAt = current.lastWatchedAt,
                    isFavorite = current.isFavorite,
                )
            }
        )
    }
}
