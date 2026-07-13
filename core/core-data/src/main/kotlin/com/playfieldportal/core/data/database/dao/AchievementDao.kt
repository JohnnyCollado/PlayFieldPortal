package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.AchievementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementDao {

    @Query("SELECT * FROM achievements WHERE game_id = :gameId")
    fun observeForGame(gameId: Long): Flow<List<AchievementEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<AchievementEntity>)

    @Query("DELETE FROM achievements WHERE game_id = :gameId")
    suspend fun deleteForGame(gameId: Long)

    @Query("SELECT COUNT(*) FROM achievements WHERE game_id = :gameId AND is_earned = 1")
    suspend fun earnedCount(gameId: Long): Int
}
