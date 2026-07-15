package com.playfieldportal.core.data.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.AccountAchievementSetEntity
import kotlinx.coroutines.flow.Flow

/**
 * A tracked set with its optional library game — the projection behind the hub's account-wide
 * lenses. [libraryGameId] is set when a provider_game_links row points at the set; [title]
 * prefers the library game's name over the provider's.
 */
data class AccountSetRow(
    val provider: String,
    @ColumnInfo(name = "provider_game_id") val providerGameId: String,
    @ColumnInfo(name = "library_game_id") val libraryGameId: Long?,
    val title: String,
    @ColumnInfo(name = "icon_url") val iconUrl: String?,
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

    // Import stubs check-before-insert (July 2026 decision): an already-synced set is never
    // clobbered back to a stub.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: AccountAchievementSetEntity)

    // Library-synced rows carry no provider art; an account import backfills it without
    // touching anything else on the row.
    @Query(
        "UPDATE account_achievement_sets SET icon_url = :iconUrl " +
            "WHERE provider = :provider AND provider_game_id = :providerGameId AND icon_url IS NULL"
    )
    suspend fun backfillIcon(provider: String, providerGameId: String, iconUrl: String)

    // The import's work queue: stubs whose full detail hasn't landed yet. Resumable by
    // construction — an interrupted import leaves exactly these rows behind.
    @Query("SELECT * FROM account_achievement_sets WHERE provider = :provider AND last_synced_at IS NULL")
    suspend fun getUnsyncedSets(provider: String): List<AccountAchievementSetEntity>

    // The account-wide Shiba wallet, derived in one pass over the summary rows: each set
    // contributes its earned coins (weighted) plus the Platinum's 300 once mastered. Matches
    // core-domain's CoinWallet math; kept in SQL so the player card reads without loading rows.
    @Query(
        "SELECT COALESCE(SUM(bronze_earned * 15 + silver_earned * 30 + gold_earned * 90 + " +
            "(CASE WHEN mastered THEN 300 ELSE 0 END)), 0) FROM account_achievement_sets"
    )
    fun observeWalletCoins(): Flow<Int>

    // Every account set — library-linked or not — for the hub's account-wide lenses. GROUP BY
    // keeps one row per set when several library copies link to the same provider identity
    // (MIN picks the surviving library game deterministically).
    @Query(
        "SELECT s.provider AS provider, s.provider_game_id AS provider_game_id, " +
            "MIN(l.game_id) AS library_game_id, COALESCE(g.title, s.title) AS title, s.icon_url AS icon_url, " +
            "s.bronze_total AS bronze_total, s.silver_total AS silver_total, s.gold_total AS gold_total, " +
            "s.bronze_earned AS bronze_earned, s.silver_earned AS silver_earned, s.gold_earned AS gold_earned, " +
            "s.mastered AS mastered " +
            "FROM account_achievement_sets s " +
            "LEFT JOIN provider_game_links l ON l.provider = s.provider AND l.provider_game_id = s.provider_game_id " +
            "LEFT JOIN games g ON g.id = l.game_id " +
            "GROUP BY s.provider, s.provider_game_id"
    )
    fun observeAccountSets(): Flow<List<AccountSetRow>>

    @Query(
        "SELECT * FROM account_achievement_sets " +
            "WHERE provider = :provider AND provider_game_id = :providerGameId LIMIT 1"
    )
    fun observeSet(provider: String, providerGameId: String): Flow<AccountAchievementSetEntity?>
}
