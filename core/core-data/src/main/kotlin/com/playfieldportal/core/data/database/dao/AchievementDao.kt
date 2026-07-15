package com.playfieldportal.core.data.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.AchievementEntity
import kotlinx.coroutines.flow.Flow

/** An earned coin joined to its game title — the projection behind the hub's rarity lens. */
data class EarnedCoinRow(
    @ColumnInfo(name = "game_id") val gameId: Long,
    @ColumnInfo(name = "game_title") val gameTitle: String,
    val title: String,
    val tier: String,
    @ColumnInfo(name = "global_rarity") val globalRarity: Double,
    @ColumnInfo(name = "icon_url") val iconUrl: String?,
)

@Dao
interface AchievementDao {

    @Query("SELECT * FROM achievements WHERE game_id = :gameId")
    fun observeForGame(gameId: Long): Flow<List<AchievementEntity>>

    @Query("SELECT * FROM achievements WHERE game_id = :gameId")
    suspend fun getForGame(gameId: Long): List<AchievementEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<AchievementEntity>)

    @Query("DELETE FROM achievements WHERE game_id = :gameId")
    suspend fun deleteForGame(gameId: Long)

    @Query("SELECT COUNT(*) FROM achievements WHERE game_id = :gameId AND is_earned = 1")
    suspend fun earnedCount(gameId: Long): Int

    // The rarest earned coins across the library (lowest global unlock rarity first), each with
    // its game title, for the hub's "Rarest Earned" lens. Coins whose provider reported no rarity
    // are stored with a negative sentinel and excluded — unknown rarity can't rank as rarest.
    @Query(
        "SELECT a.game_id AS game_id, g.title AS game_title, a.title AS title, a.tier AS tier, " +
            "a.global_rarity AS global_rarity, a.icon_url AS icon_url " +
            "FROM achievements a JOIN games g ON g.id = a.game_id " +
            "WHERE a.is_earned = 1 AND a.global_rarity >= 0 " +
            "ORDER BY a.global_rarity ASC, a.title ASC LIMIT :limit"
    )
    fun observeRarestEarned(limit: Int): Flow<List<EarnedCoinRow>>
}
