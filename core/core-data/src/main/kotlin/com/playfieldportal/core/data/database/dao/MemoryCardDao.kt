package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.MemoryCardEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryCardDao {

    // Pinned cards first, then manual sort order, then display name as a stable tiebreak.
    @Query("SELECT * FROM memory_cards ORDER BY pinned DESC, sort_order ASC, display_name ASC")
    fun observeAll(): Flow<List<MemoryCardEntity>>

    @Query("SELECT * FROM memory_cards WHERE enabled = 1 ORDER BY pinned DESC, sort_order ASC, display_name ASC")
    fun observeEnabled(): Flow<List<MemoryCardEntity>>

    @Query("SELECT * FROM memory_cards ORDER BY pinned DESC, sort_order ASC, display_name ASC")
    suspend fun getAll(): List<MemoryCardEntity>

    @Query("SELECT * FROM memory_cards WHERE platform_id = :platformId")
    suspend fun getById(platformId: String): MemoryCardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: MemoryCardEntity)

    @Query("DELETE FROM memory_cards WHERE platform_id = :platformId")
    suspend fun delete(platformId: String)

    @Query("UPDATE memory_cards SET enabled = :enabled WHERE platform_id = :platformId")
    suspend fun setEnabled(platformId: String, enabled: Boolean)

    @Query("UPDATE memory_cards SET pinned = :pinned WHERE platform_id = :platformId")
    suspend fun setPinned(platformId: String, pinned: Boolean)

    @Query("UPDATE memory_cards SET display_name = :name WHERE platform_id = :platformId")
    suspend fun setDisplayName(platformId: String, name: String)

    @Query("UPDATE memory_cards SET rom_directory = :dir WHERE platform_id = :platformId")
    suspend fun setRomDirectory(platformId: String, dir: String?)

    @Query("UPDATE memory_cards SET emulator_id = :emulatorId WHERE platform_id = :platformId")
    suspend fun setEmulator(platformId: String, emulatorId: String?)

    @Query("UPDATE memory_cards SET sort_order = :order WHERE platform_id = :platformId")
    suspend fun setSortOrder(platformId: String, order: Int)

    @Query("UPDATE memory_cards SET last_scanned_at = :scannedAt, game_count = :gameCount WHERE platform_id = :platformId")
    suspend fun updateScanResult(platformId: String, scannedAt: Long, gameCount: Int)

    @Query("UPDATE memory_cards SET game_count = :gameCount WHERE platform_id = :platformId")
    suspend fun updateGameCount(platformId: String, gameCount: Int)

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM memory_cards")
    suspend fun maxSortOrder(): Int
}
