package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.VideoEntity
import com.playfieldportal.core.data.database.entity.VideoPlaylistEntity
import com.playfieldportal.core.data.database.entity.VideoPlaylistItemEntity
import kotlinx.coroutines.flow.Flow

// Playlist + its current video count, so the playlist list renders without N queries.
data class VideoPlaylistWithCount(
    @Embedded val playlist: VideoPlaylistEntity,
    val video_count: Int,
)

@Dao
interface VideoPlaylistDao {

    @Query(
        """
        SELECT p.*, (
            SELECT COUNT(*) FROM video_playlist_items pi WHERE pi.playlist_id = p.id
        ) AS video_count
        FROM video_playlists p
        ORDER BY p.sort_order ASC, p.created_at ASC
        """
    )
    fun observeAllWithCounts(): Flow<List<VideoPlaylistWithCount>>

    @Query("SELECT * FROM video_playlists WHERE id = :id")
    suspend fun getById(id: Long): VideoPlaylistEntity?

    // A playlist's videos in manual order. INNER JOIN drops orphaned rows (a video a re-scan
    // removed) automatically.
    @Query(
        """
        SELECT v.* FROM videos v
        INNER JOIN video_playlist_items pi ON pi.video_id = v.id
        WHERE pi.playlist_id = :playlistId
        ORDER BY pi.position ASC, pi.added_at ASC
        """
    )
    fun observeVideos(playlistId: Long): Flow<List<VideoEntity>>

    @Query("SELECT playlist_id FROM video_playlist_items WHERE video_id = :videoId")
    suspend fun getPlaylistIdsForVideo(videoId: String): List<Long>

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM video_playlists")
    suspend fun maxSortOrder(): Int

    @Query("SELECT COALESCE(MAX(position), -1) FROM video_playlist_items WHERE playlist_id = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Query("SELECT COUNT(*) FROM video_playlist_items WHERE playlist_id = :playlistId AND video_id = :videoId")
    suspend fun isVideoInPlaylist(playlistId: Long, videoId: String): Int

    @Insert
    suspend fun insert(playlist: VideoPlaylistEntity): Long

    @Query("UPDATE video_playlists SET name = :name, updated_at = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, name: String, updatedAt: Long)

    @Query("DELETE FROM video_playlists WHERE id = :id")
    suspend fun delete(id: Long)

    // Re-adding an existing membership is a no-op (composite PK + IGNORE).
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addVideo(join: VideoPlaylistItemEntity)

    @Query("DELETE FROM video_playlist_items WHERE playlist_id = :playlistId AND video_id = :videoId")
    suspend fun removeVideo(playlistId: Long, videoId: String)

    @Query("UPDATE video_playlists SET updated_at = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)
}
