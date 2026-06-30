package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.MusicTrackEntity
import com.playfieldportal.core.data.database.entity.PlaylistEntity
import com.playfieldportal.core.data.database.entity.PlaylistTrackEntity
import kotlinx.coroutines.flow.Flow

// Playlist + its current track count, so the playlist list renders without N queries.
data class PlaylistWithCount(
    @Embedded val playlist: PlaylistEntity,
    val track_count: Int,
)

@Dao
interface PlaylistDao {

    @Query(
        """
        SELECT p.*, (
            SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlist_id = p.id
        ) AS track_count
        FROM playlists p
        ORDER BY p.sort_order ASC, p.created_at ASC
        """
    )
    fun observeAllWithCounts(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: Long): PlaylistEntity?

    // A playlist's tracks, in the user's manual order. Joining drops orphaned rows (a track that
    // a re-scan removed) automatically since the INNER JOIN finds no matching music_tracks row.
    @Query(
        """
        SELECT t.* FROM music_tracks t
        INNER JOIN playlist_tracks pt ON pt.track_id = t.id
        WHERE pt.playlist_id = :playlistId
        ORDER BY pt.position ASC, pt.added_at ASC
        """
    )
    fun observeTracks(playlistId: Long): Flow<List<MusicTrackEntity>>

    @Query("SELECT playlist_id FROM playlist_tracks WHERE track_id = :trackId")
    suspend fun getPlaylistIdsForTrack(trackId: String): List<Long>

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM playlists")
    suspend fun maxSortOrder(): Int

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlist_id = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlist_id = :playlistId AND track_id = :trackId")
    suspend fun isTrackInPlaylist(playlistId: Long, trackId: String): Int

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, updated_at = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, name: String, updatedAt: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    // Re-adding an existing membership is a no-op (composite PK + IGNORE).
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTrack(join: PlaylistTrackEntity)

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId AND track_id = :trackId")
    suspend fun removeTrack(playlistId: Long, trackId: String)

    @Query("UPDATE playlists SET updated_at = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)
}
