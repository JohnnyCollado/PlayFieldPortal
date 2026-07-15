package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * One individual coin (achievement) of a game's set: its tier, rarity, and this user's earned
 * state. The Platinum crown is never a row here — it is the set-completion award tracked on
 * [AchievementSetEntity.mastered].
 *
 * Composite key (game_id, provider, provider_achievement_id): a provider's achievement id is only
 * unique within its game (Steam apiname collisions across apps), so the game id is part of the key.
 * Cascade-deleted with its game. See docs/shiba-coins-achievements-plan.md.
 */
@Entity(
    tableName = "achievements",
    primaryKeys = ["game_id", "provider", "provider_achievement_id"],
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["game_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AchievementEntity(
    @ColumnInfo(name = "game_id")
    val gameId: Long,

    // AchievementProvider enum name (RETRO_ACHIEVEMENTS / STEAM).
    val provider: String,

    // The provider's own achievement identifier (RA achievement id / Steam apiname).
    @ColumnInfo(name = "provider_achievement_id")
    val providerAchievementId: String,

    val title: String,
    val description: String,

    // ShibaTier enum name. PLATINUM only for a provider-declared completion achievement (its coin
    // IS the crown); the synthetic 100% crown stays on AchievementSetEntity.mastered, not a row.
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
