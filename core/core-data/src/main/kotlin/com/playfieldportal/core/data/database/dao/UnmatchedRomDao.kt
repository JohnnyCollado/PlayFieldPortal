package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.playfieldportal.core.data.database.entity.UnmatchedRomEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UnmatchedRomDao {

    @Query("SELECT * FROM unmatched_roms ORDER BY file_name ASC")
    fun observeAll(): Flow<List<UnmatchedRomEntity>>

    @Query("SELECT COUNT(*) FROM unmatched_roms WHERE resolved_platform_id IS NULL")
    fun observeUnresolvedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(roms: List<UnmatchedRomEntity>)

    @Update
    suspend fun update(rom: UnmatchedRomEntity)

    // User assigns a platform — caller then creates a game entry and deletes this row
    @Query("UPDATE unmatched_roms SET resolved_platform_id = :platformId WHERE id = :id")
    suspend fun assignPlatform(id: Long, platformId: String)

    @Query("DELETE FROM unmatched_roms WHERE id = :id")
    suspend fun deleteById(id: Long)

    // Prune entries whose files no longer exist on disk — called during missing ROM check
    @Query("DELETE FROM unmatched_roms WHERE file_path IN (:missingPaths)")
    suspend fun deleteMissing(missingPaths: List<String>)
}
