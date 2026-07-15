package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * One individual coin (achievement) of a set: its tier, rarity, and this user's earned state.
 * Keyed by provider identity like its parent [AccountAchievementSetEntity]; the achievement id is
 * only unique within its game (Steam apiname collisions across apps), so the game id is part of
 * the key. The Platinum crown is never a row here — it is the set-completion award tracked on
 * [AccountAchievementSetEntity.mastered].
 */
@Entity(
    tableName = "account_achievements",
    primaryKeys = ["provider", "provider_game_id", "provider_achievement_id"],
)
data class AccountAchievementEntity(
    // AchievementProvider enum name.
    val provider: String,

    @ColumnInfo(name = "provider_game_id")
    val providerGameId: String,

    // The provider's own achievement identifier (RA achievement id / Steam apiname).
    @ColumnInfo(name = "provider_achievement_id")
    val providerAchievementId: String,

    val title: String,
    val description: String,

    // ShibaTier enum name. PLATINUM only for a provider-declared completion achievement (its coin
    // IS the crown); the synthetic 100% crown stays on AccountAchievementSetEntity.mastered.
    val tier: String,

    // Global unlock rarity, percent of players who own it (0..100). Negative = the provider
    // reported no percentage (SyncedCoin.RARITY_UNAVAILABLE); excluded from rarity rankings.
    @ColumnInfo(name = "global_rarity")
    val globalRarity: Double,

    @ColumnInfo(name = "icon_url")
    val iconUrl: String? = null,

    // A hidden/secret coin: description stays redacted in the UI until it is earned.
    @ColumnInfo(name = "is_hidden")
    val isHidden: Boolean = false,

    @ColumnInfo(name = "is_earned")
    val isEarned: Boolean = false,

    @ColumnInfo(name = "earned_at")
    val earnedAt: Long? = null,
)
