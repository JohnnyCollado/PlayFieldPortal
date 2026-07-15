package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * One game's achievement set on one provider, keyed by the provider's own identity — the single
 * source of truth for coin summaries whether or not the game exists in the library. Library games
 * resolve here through [ProviderGameLinkEntity]; account-imported games have no link and live here
 * alone. Per-coin detail lives in [AccountAchievementEntity].
 *
 * Constraint the schema can't show: rows are never cascade-deleted with a library game — account
 * history outlives library membership, and provider disconnect keeps data cached (July 2026
 * decision). See docs/account-achievements-plan.md.
 */
@Entity(
    tableName = "account_achievement_sets",
    primaryKeys = ["provider", "provider_game_id"],
)
data class AccountAchievementSetEntity(
    // AchievementProvider enum name.
    val provider: String,

    // The provider's own game identifier (RA game id / Steam appid), as text.
    @ColumnInfo(name = "provider_game_id")
    val providerGameId: String,

    // The provider's title for the game, shown for entries with no library game.
    val title: String,

    // Provider game art (RA icon / Steam capsule) for non-library hub rows.
    @ColumnInfo(name = "icon_url")
    val iconUrl: String? = null,

    @ColumnInfo(name = "bronze_total")
    val bronzeTotal: Int = 0,
    @ColumnInfo(name = "silver_total")
    val silverTotal: Int = 0,
    @ColumnInfo(name = "gold_total")
    val goldTotal: Int = 0,

    @ColumnInfo(name = "bronze_earned")
    val bronzeEarned: Int = 0,
    @ColumnInfo(name = "silver_earned")
    val silverEarned: Int = 0,
    @ColumnInfo(name = "gold_earned")
    val goldEarned: Int = 0,

    // True once every individual coin is earned — the game has won its Platinum crown.
    val mastered: Boolean = false,

    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long? = null,
)
