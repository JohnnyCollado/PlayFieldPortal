package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * Why a game failed to link during the last auto-match — the specific reason its ROM/disc couldn't
 * be hashed or matched (couldn't read the file, unsupported disc format, hash not registered, no
 * title match, …). Surfaced in the hub's "Untracked" section so the user knows what went wrong,
 * not just that it's untracked. One row per game; cascade-deleted with its game.
 */
@Entity(
    tableName = "achievement_match_notes",
    primaryKeys = ["game_id"],
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["game_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AchievementMatchNoteEntity(
    @ColumnInfo(name = "game_id")
    val gameId: Long,

    // A user-facing sentence explaining the failure.
    val reason: String,

    @ColumnInfo(name = "checked_at")
    val checkedAt: Long,
)
