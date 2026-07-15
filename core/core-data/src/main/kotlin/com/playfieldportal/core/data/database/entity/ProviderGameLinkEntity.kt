package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * Maps a library game to its identifier on an achievement provider — the bridge through which
 * game-keyed reads resolve to account-keyed achievement rows. Composite key (game_id, provider):
 * a game may hold one link per provider (STEAM and LOCAL_STEAM coexist for owned games played
 * through an emulator — July 2026 decision). Set automatically or by hand; cascade-deleted with
 * its game, while the account rows it points to survive.
 * See docs/local-steam-achievements-plan.md and docs/account-achievements-plan.md.
 */
@Entity(
    tableName = "provider_game_links",
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
data class ProviderGameLinkEntity(
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
