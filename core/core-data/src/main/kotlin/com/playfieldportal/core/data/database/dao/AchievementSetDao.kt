package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.AchievementSetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementSetDao {

    @Query("SELECT * FROM achievement_sets WHERE game_id = :gameId LIMIT 1")
    fun observeForGame(gameId: Long): Flow<AchievementSetEntity?>

    @Query("SELECT * FROM achievement_sets")
    fun observeAll(): Flow<List<AchievementSetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AchievementSetEntity)

    @Query("DELETE FROM achievement_sets WHERE game_id = :gameId")
    suspend fun deleteForGame(gameId: Long)

    // The account-wide Shiba wallet, derived in one pass over the per-game summary rows: each set
    // contributes its earned coins (weighted) plus the Platinum's 300 once mastered. Matches
    // core-domain's CoinWallet math; kept in SQL so the player card reads without loading rows.
    @Query(
        "SELECT COALESCE(SUM(bronze_earned * 15 + silver_earned * 30 + gold_earned * 90 + " +
            "(CASE WHEN mastered THEN 300 ELSE 0 END)), 0) FROM achievement_sets"
    )
    fun observeWalletCoins(): Flow<Int>

    @Query("SELECT COUNT(*) FROM achievement_sets")
    suspend fun count(): Int
}
