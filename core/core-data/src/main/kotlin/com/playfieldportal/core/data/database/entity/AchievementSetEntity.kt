package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * One game's achievement set from one provider: the denormalized tier counts and sync metadata
 * that back the O(1) coin-summary glance (progress strip, player-card wallet) without scanning
 * every [AchievementEntity] row. The per-coin detail lives in [AchievementEntity].
 *
 * Composite key (game_id, provider): a game has one set per provider (RA for ROMs, Steam for PC).
 * Cascade-deleted with its game. See docs/shiba-coins-achievements-plan.md.
 */
@Entity(
    tableName = "achievement_sets",
    primaryKeys = ["game_id", "provider"],
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["game_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AchievementSetEntity(
    @ColumnInfo(name = "game_id")
    val gameId: Long,

    // AchievementProvider enum name (RETRO_ACHIEVEMENTS / STEAM).
    val provider: String,

    // The provider's own game identifier (RA game id / Steam appid), as text.
    @ColumnInfo(name = "provider_game_id")
    val providerGameId: String,

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
