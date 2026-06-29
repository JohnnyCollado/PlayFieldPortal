package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.MusicFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicFolderDao {

    @Query("SELECT * FROM music_folders ORDER BY display_name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<MusicFolderEntity>>

    @Query("SELECT * FROM music_folders WHERE enabled = 1 ORDER BY display_name COLLATE NOCASE ASC")
    fun observeEnabled(): Flow<List<MusicFolderEntity>>

    @Query("SELECT * FROM music_folders ORDER BY display_name COLLATE NOCASE ASC")
    suspend fun getAll(): List<MusicFolderEntity>

    @Query("SELECT * FROM music_folders WHERE id = :id")
    suspend fun getById(id: String): MusicFolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: MusicFolderEntity)

    @Query("DELETE FROM music_folders WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE music_folders SET enabled = :enabled, updated_at = :now WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, now: Long)

    @Query("UPDATE music_folders SET display_name = :name, updated_at = :now WHERE id = :id")
    suspend fun setDisplayName(id: String, name: String, now: Long)

    @Query("UPDATE music_folders SET track_count = :count, last_scanned_at = :scannedAt, updated_at = :scannedAt WHERE id = :id")
    suspend fun updateScanResult(id: String, count: Int, scannedAt: Long)
}
