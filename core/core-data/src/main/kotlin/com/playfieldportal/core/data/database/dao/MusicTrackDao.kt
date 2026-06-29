package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.playfieldportal.core.data.database.entity.MusicTrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicTrackDao {

    // Library-wide ordering: artist, then album, then title/displayName for a stable, musical sort.
    @Query(
        """
        SELECT * FROM music_tracks
        ORDER BY artist COLLATE NOCASE ASC,
                 album COLLATE NOCASE ASC,
                 track_number ASC,
                 COALESCE(title, display_name) COLLATE NOCASE ASC
        """
    )
    fun observeAll(): Flow<List<MusicTrackEntity>>

    @Query(
        """
        SELECT * FROM music_tracks
        WHERE folder_id = :folderId
        ORDER BY artist COLLATE NOCASE ASC,
                 album COLLATE NOCASE ASC,
                 track_number ASC,
                 COALESCE(title, display_name) COLLATE NOCASE ASC
        """
    )
    fun observeByFolder(folderId: String): Flow<List<MusicTrackEntity>>

    @Query("SELECT * FROM music_tracks WHERE id = :id")
    suspend fun getById(id: String): MusicTrackEntity?

    @Query("SELECT COUNT(*) FROM music_tracks WHERE folder_id = :folderId")
    suspend fun countForFolder(folderId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<MusicTrackEntity>)

    @Query("DELETE FROM music_tracks WHERE folder_id = :folderId")
    suspend fun deleteForFolder(folderId: String)

    // Replaces a single folder's tracks atomically; other folders are never touched.
    @Transaction
    suspend fun replaceForFolder(folderId: String, tracks: List<MusicTrackEntity>) {
        deleteForFolder(folderId)
        if (tracks.isNotEmpty()) insertAll(tracks)
    }
}
