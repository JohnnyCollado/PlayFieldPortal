package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.playfieldportal.core.data.database.entity.LibrarySourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LibrarySourceDao {

    @Query("SELECT * FROM library_sources ORDER BY label ASC")
    fun observeAll(): Flow<List<LibrarySourceEntity>>

    @Query("SELECT * FROM library_sources WHERE is_enabled = 1")
    suspend fun getEnabled(): List<LibrarySourceEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(source: LibrarySourceEntity): Long

    @Update
    suspend fun update(source: LibrarySourceEntity)

    @Query("DELETE FROM library_sources WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM library_sources WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("UPDATE library_sources SET is_enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("""
        UPDATE library_sources
        SET last_scanned_at = :scannedAt, game_count = :gameCount
        WHERE id = :id
    """)
    suspend fun updateScanResult(id: Long, scannedAt: Long, gameCount: Int)

    @Query("SELECT * FROM library_sources WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): LibrarySourceEntity?
}
