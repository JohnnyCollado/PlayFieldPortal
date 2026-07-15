package com.playfieldportal.core.data.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.AccountAchievementSetEntity
import kotlinx.coroutines.flow.Flow

/** A tracked set joined to its library game — the projection behind the hub's mastery lens. */
data class GameSetRow(
    @ColumnInfo(name = "game_id") val gameId: Long,
    val title: String,
    val provider: String,
    @ColumnInfo(name = "bronze_total") val bronzeTotal: Int,
    @ColumnInfo(name = "silver_total") val silverTotal: Int,
    @ColumnInfo(name = "gold_total") val goldTotal: Int,
    @ColumnInfo(name = "bronze_earned") val bronzeEarned: Int,
    @ColumnInfo(name = "silver_earned") val silverEarned: Int,
    @ColumnInfo(name = "gold_earned") val goldEarned: Int,
    val mastered: Boolean,
)

@Dao
interface AccountAchievementSetDao {

    // Game-keyed reads resolve through provider_game_links: the account row IS the library
    // game's coin data whenever a link points at it.
    @Query(
        "SELECT s.* FROM account_achievement_sets s " +
            "JOIN provider_game_links l ON l.provider = s.provider AND l.provider_game_id = s.provider_game_id " +
            "WHERE l.game_id = :gameId LIMIT 1"
    )
    fun observeForGame(gameId: Long): Flow<AccountAchievementSetEntity?>

    @Query(
        "SELECT * FROM account_achievement_sets " +
            "WHERE provider = :provider AND provider_game_id = :providerGameId LIMIT 1"
    )
    suspend fun getSet(provider: String, providerGameId: String): AccountAchievementSetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AccountAchievementSetEntity)

    // The account-wide Shiba wallet, derived in one pass over the summary rows: each set
    // contributes its earned coins (weighted) plus the Platinum's 300 once mastered. Matches
    // core-domain's CoinWallet math; kept in SQL so the player card reads without loading rows.
    @Query(
        "SELECT COALESCE(SUM(bronze_earned * 15 + silver_earned * 30 + gold_earned * 90 + " +
            "(CASE WHEN mastered THEN 300 ELSE 0 END)), 0) FROM account_achievement_sets"
    )
    fun observeWalletCoins(): Flow<Int>

    // Every set linked to a library game, with that game's title, for the hub's mastery /
    // all-tracked lenses. Coin-weighted ordering is applied in the repository (small row count).
    @Query(
        "SELECT l.game_id AS game_id, g.title AS title, s.provider AS provider, " +
            "s.bronze_total AS bronze_total, s.silver_total AS silver_total, s.gold_total AS gold_total, " +
            "s.bronze_earned AS bronze_earned, s.silver_earned AS silver_earned, s.gold_earned AS gold_earned, " +
            "s.mastered AS mastered " +
            "FROM account_achievement_sets s " +
            "JOIN provider_game_links l ON l.provider = s.provider AND l.provider_game_id = s.provider_game_id " +
            "JOIN games g ON g.id = l.game_id"
    )
    fun observeGameSets(): Flow<List<GameSetRow>>
}
