package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.ArtworkRecordEntity

@Dao
interface ArtworkRecordDao {

    // REPLACE rides the unique (game_id, artwork_type) index — one record per game per type.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<ArtworkRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ArtworkRecordEntity)

    @Query("SELECT * FROM artwork_records WHERE game_id = :gameId")
    suspend fun getForGame(gameId: Long): List<ArtworkRecordEntity>

    @Query("SELECT * FROM artwork_records WHERE game_id = :gameId AND artwork_type = :type")
    suspend fun get(gameId: Long, type: String): ArtworkRecordEntity?

    // Write-time collision check: another game already using this portable name for this
    // platform/type (case-insensitive — FAT volumes are).
    @Query("""
        SELECT * FROM artwork_records
        WHERE platform_id = :platformId AND artwork_type = :type
          AND portable_name = :portableName COLLATE NOCASE AND game_id != :gameId
    """)
    suspend fun findNameCollisions(platformId: String, type: String, portableName: String, gameId: Long): List<ArtworkRecordEntity>

    @Query("SELECT COUNT(*) FROM artwork_records")
    suspend fun count(): Int

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM artwork_records")
    suspend fun totalBytes(): Long

    @Query("DELETE FROM artwork_records WHERE game_id = :gameId")
    suspend fun deleteForGame(gameId: Long)

    @Query("DELETE FROM artwork_records")
    suspend fun clear()
}
