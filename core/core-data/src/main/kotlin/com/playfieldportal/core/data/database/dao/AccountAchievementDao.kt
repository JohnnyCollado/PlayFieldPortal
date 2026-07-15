package com.playfieldportal.core.data.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.AccountAchievementEntity
import kotlinx.coroutines.flow.Flow

/** An earned coin joined to its library game title — the projection behind the hub's rarity lens. */
data class EarnedCoinRow(
    @ColumnInfo(name = "game_id") val gameId: Long,
    @ColumnInfo(name = "game_title") val gameTitle: String,
    val title: String,
    val tier: String,
    @ColumnInfo(name = "global_rarity") val globalRarity: Double,
    @ColumnInfo(name = "icon_url") val iconUrl: String?,
)

@Dao
interface AccountAchievementDao {

    // Game-keyed read resolves through provider_game_links, same as the set summary.
    @Query(
        "SELECT a.* FROM account_achievements a " +
            "JOIN provider_game_links l ON l.provider = a.provider AND l.provider_game_id = a.provider_game_id " +
            "WHERE l.game_id = :gameId"
    )
    fun observeForGame(gameId: Long): Flow<List<AccountAchievementEntity>>

    @Query(
        "SELECT * FROM account_achievements " +
            "WHERE provider = :provider AND provider_game_id = :providerGameId"
    )
    suspend fun getForSet(provider: String, providerGameId: String): List<AccountAchievementEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<AccountAchievementEntity>)

    // Scoped to one set so a sync can never wipe another provider's coins for the same game
    // (a STEAM and a LOCAL_STEAM set may share a library game).
    @Query(
        "DELETE FROM account_achievements " +
            "WHERE provider = :provider AND provider_game_id = :providerGameId"
    )
    suspend fun deleteForSet(provider: String, providerGameId: String)

    // The rarest earned coins across linked library games (lowest global unlock rarity first),
    // each with its game title, for the hub's "Rarest Earned" lens. Coins whose provider reported
    // no rarity are stored with a negative sentinel and excluded — unknown rarity can't rank.
    @Query(
        "SELECT l.game_id AS game_id, g.title AS game_title, a.title AS title, a.tier AS tier, " +
            "a.global_rarity AS global_rarity, a.icon_url AS icon_url " +
            "FROM account_achievements a " +
            "JOIN provider_game_links l ON l.provider = a.provider AND l.provider_game_id = a.provider_game_id " +
            "JOIN games g ON g.id = l.game_id " +
            "WHERE a.is_earned = 1 AND a.global_rarity >= 0 " +
            "ORDER BY a.global_rarity ASC, a.title ASC LIMIT :limit"
    )
    fun observeRarestEarned(limit: Int): Flow<List<EarnedCoinRow>>
}
