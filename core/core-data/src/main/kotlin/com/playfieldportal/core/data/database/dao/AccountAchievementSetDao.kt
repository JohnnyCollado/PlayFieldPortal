package com.playfieldportal.core.data.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.playfieldportal.core.data.database.entity.AccountAchievementEntity
import com.playfieldportal.core.data.database.entity.AccountAchievementSetEntity
import kotlinx.coroutines.flow.Flow

/**
 * A tracked set with its optional library game — the projection behind the hub's account-wide
 * lenses. Only sets whose identity is in the confirmed-local-match ledger appear. [libraryGameId]
 * is set when a provider_game_links row points at the set; [title] prefers the library game's name
 * over the provider's; [isPresent] is false for a removed game kept as history.
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
    @ColumnInfo(name = "is_present") val isPresent: Boolean = true,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long? = null,
)

/** A present, matched library game whose identity has never been checked — "Awaiting sync". */
data class AwaitingSyncRow(
    @ColumnInfo(name = "game_id") val gameId: Long,
    val provider: String,
    @ColumnInfo(name = "provider_game_id") val providerGameId: String,
    val title: String,
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

    /** Every stored set, legacy imports included (backup and tests). */
    @Query("SELECT * FROM account_achievement_sets")
    suspend fun getAllSets(): List<AccountAchievementSetEntity>

    @Query("DELETE FROM account_achievement_sets WHERE provider = :provider AND provider_game_id = :providerGameId")
    suspend fun deleteSet(provider: String, providerGameId: String)

    // The account-wide Shiba wallet, derived in one pass over the summary rows: each confirmed
    // set (present or history) contributes its earned coins (weighted) plus the Platinum's 300
    // once mastered. A set outside the ledger — an old account import — contributes nothing.
    // Matches core-domain's CoinWallet math; kept in SQL so the player card reads without loading rows.
    @Query(
        "SELECT COALESCE(SUM(s.bronze_earned * 15 + s.silver_earned * 30 + s.gold_earned * 90 + " +
            "(CASE WHEN s.mastered THEN 300 ELSE 0 END)), 0) FROM account_achievement_sets s " +
            "JOIN achievement_tracked_identities t " +
            "ON t.provider = s.provider AND t.provider_game_id = s.provider_game_id"
    )
    fun observeWalletCoins(): Flow<Int>

    // Every confirmed set — library-linked or not, present or history — for the hub's
    // account-wide lenses. GROUP BY
    // keeps one row per set when several library copies link to the same provider identity
    // (MIN picks the surviving library game deterministically).
    @Query(
        "SELECT s.provider AS provider, s.provider_game_id AS provider_game_id, " +
            "MIN(l.game_id) AS library_game_id, COALESCE(g.title, s.title) AS title, s.icon_url AS icon_url, " +
            "s.bronze_total AS bronze_total, s.silver_total AS silver_total, s.gold_total AS gold_total, " +
            "s.bronze_earned AS bronze_earned, s.silver_earned AS silver_earned, s.gold_earned AS gold_earned, " +
            "s.mastered AS mastered, t.is_present AS is_present, s.last_synced_at AS last_synced_at " +
            "FROM account_achievement_sets s " +
            "JOIN achievement_tracked_identities t " +
            "ON t.provider = s.provider AND t.provider_game_id = s.provider_game_id " +
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCoins(items: List<AccountAchievementEntity>)

    @Query("DELETE FROM account_achievements WHERE provider = :provider AND provider_game_id = :providerGameId")
    suspend fun deleteCoins(provider: String, providerGameId: String)

    /**
     * Replaces a set's coins and summary in one transaction, so an interrupted write can never
     * leave the set visible with no coins (or new coins under an old summary).
     */
    @Transaction
    suspend fun replaceSet(set: AccountAchievementSetEntity, coins: List<AccountAchievementEntity>) {
        deleteCoins(set.provider, set.providerGameId)
        upsertCoins(coins)
        upsert(set)
    }

    // Present, linked library games with no set whose identity was never checked: matched but
    // awaiting their first sync (e.g. right after Clear all tracked achievements). A checked
    // identity with no set — the provider has no achievements for it — is not awaiting anything.
    @Query(
        "SELECT MIN(l.game_id) AS game_id, l.provider AS provider, l.provider_game_id AS provider_game_id, " +
            "COALESCE(g.user_title_override, g.title) AS title " +
            "FROM provider_game_links l JOIN games g ON g.id = l.game_id " +
            "LEFT JOIN account_achievement_sets s " +
            "ON s.provider = l.provider AND s.provider_game_id = l.provider_game_id " +
            "LEFT JOIN achievement_tracked_identities t " +
            "ON t.provider = l.provider AND t.provider_game_id = l.provider_game_id " +
            "WHERE g.is_missing = 0 AND s.provider IS NULL AND t.last_checked_at IS NULL " +
            "GROUP BY l.provider, l.provider_game_id"
    )
    fun observeAwaitingSync(): Flow<List<AwaitingSyncRow>>
}
