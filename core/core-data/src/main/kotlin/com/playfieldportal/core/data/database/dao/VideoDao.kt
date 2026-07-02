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

    @Query("SELECT COUNT(*) FROM videos WHERE library_id = :libraryId")
    suspend fun countForLibrary(libraryId: String): Int

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
    @Transaction
    suspend fun replaceForLibrary(libraryId: String, videos: List<VideoEntity>) {
        deleteForLibrary(libraryId)
        if (videos.isNotEmpty()) insertAll(videos)
    }
}
