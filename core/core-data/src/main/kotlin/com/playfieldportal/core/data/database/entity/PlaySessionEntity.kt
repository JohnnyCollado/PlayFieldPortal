package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.playfieldportal.core.domain.model.PlaySession
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "play_sessions",
    foreignKeys = [
        ForeignKey(
            entity        = GameEntity::class,
            parentColumns = ["id"],
            childColumns  = ["game_id"],
            onDelete      = ForeignKey.CASCADE,     // sessions removed when game deleted
        )
    ],
    indices = [
        Index("game_id"),
        Index("platform_id"),
        Index("launched_at"),                       // for recently played queries
    ]
)
data class PlaySessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "game_id")
    val gameId: Long,

    @ColumnInfo(name = "platform_id")
    val platformId: String,

    @ColumnInfo(name = "launched_at")
    val launchedAt: Long,

    @ColumnInfo(name = "duration_millis")
    val durationMillis: Long = 0,
)

fun PlaySessionEntity.toDomain() = PlaySession(
    id              = id,
    gameId          = gameId,
    platformId      = platformId,
    launchedAt      = launchedAt,
    durationMillis  = durationMillis,
)

fun PlaySession.toEntity() = PlaySessionEntity(
    id              = id,
    gameId          = gameId,
    platformId      = platformId,
    launchedAt      = launchedAt,
    durationMillis  = durationMillis,
)
