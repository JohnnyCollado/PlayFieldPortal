package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Maps a library game to its identifier on an achievement provider, so a sync knows what to fetch.
 * A game has at most one link (a ROM is RetroAchievements, a PC game is Steam). Set automatically
 * (Steam title match) or by hand; cascade-deleted with its game.
 * See docs/shiba-coins-achievements-plan.md.
 */
@Entity(
    tableName = "provider_game_links",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["game_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ProviderGameLinkEntity(
    @PrimaryKey
    @ColumnInfo(name = "game_id")
    val gameId: Long,

    // AchievementProvider enum name.
    val provider: String,

    // The provider's game identifier (RA game id / Steam appid).
    @ColumnInfo(name = "provider_game_id")
    val providerGameId: String,

    // How the link was made: MANUAL or STEAM_TITLE.
    val source: String,

    @ColumnInfo(name = "resolved_at")
    val resolvedAt: Long,
)
