package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One game the connected Steam account owns — the cached GetOwnedGames row. Shared component:
 * the account import walks it, and the local-steam four-state model looks appids up in it
 * (docs/local-steam-achievements-plan.md section 5). Rebuilt on every import; losing it costs
 * one API call, never data.
 */
@Entity(tableName = "steam_owned_games")
data class SteamOwnedGameEntity(
    @PrimaryKey
    val appid: String,

    val name: String,

    @ColumnInfo(name = "playtime_forever_minutes")
    val playtimeForeverMinutes: Long,

    // Playtime at the last successful coin sync/probe. NULL = never processed; a difference
    // from playtime_forever_minutes marks the game for the cheap incremental re-sync.
    @ColumnInfo(name = "synced_playtime_minutes")
    val syncedPlaytimeMinutes: Long? = null,

    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Long,
)

/**
 * An appid probed once and found to have no achievement schema — never probed again, so the
 * expensive first import's calls all count exactly once.
 */
@Entity(tableName = "steam_no_achievements")
data class SteamNoAchievementsEntity(
    @PrimaryKey
    val appid: String,

    @ColumnInfo(name = "checked_at")
    val checkedAt: Long,
)
