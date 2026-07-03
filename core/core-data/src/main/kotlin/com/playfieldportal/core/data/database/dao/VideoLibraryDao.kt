package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.VideoLibraryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoLibraryDao {

    @Query("SELECT * FROM video_libraries ORDER BY display_name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<VideoLibraryEntity>>

    @Query("SELECT * FROM video_libraries WHERE enabled = 1 ORDER BY display_name COLLATE NOCASE ASC")
    fun observeEnabled(): Flow<List<VideoLibraryEntity>>

    @Query("SELECT * FROM video_libraries ORDER BY display_name COLLATE NOCASE ASC")
    suspend fun getAll(): List<VideoLibraryEntity>

    @Query("SELECT * FROM video_libraries WHERE id = :id")
    suspend fun getById(id: String): VideoLibraryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(library: VideoLibraryEntity)

    @Query("DELETE FROM video_libraries WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE video_libraries SET enabled = :enabled, updated_at = :now WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, now: Long)

    @Query("UPDATE video_libraries SET display_name = :name, updated_at = :now WHERE id = :id")
    suspend fun setDisplayName(id: String, name: String, now: Long)

    @Query("UPDATE video_libraries SET tree_uri = :treeUri, updated_at = :now WHERE id = :id")
    suspend fun setTreeUri(id: String, treeUri: String, now: Long)

    @Query("UPDATE video_libraries SET artwork_uri = :uri, updated_at = :now WHERE id = :id")
    suspend fun setArtwork(id: String, uri: String?, now: Long)

    @Query("UPDATE video_libraries SET video_count = :count, last_scanned_at = :scannedAt, updated_at = :scannedAt WHERE id = :id")
    suspend fun updateScanResult(id: String, count: Int, scannedAt: Long)
}
