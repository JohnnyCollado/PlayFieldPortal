package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import kotlinx.serialization.Serializable

/**
 * Join table for the many-to-many between collections and games. A composite primary key
 * (collectionId, gameId) makes membership idempotent — re-adding a game is a no-op. Cascade
 * deletes keep the table tidy when a collection or game is removed; game records are never
 * touched, only their membership rows.
 */
@Serializable
@Entity(
    tableName = "collection_games",
    primaryKeys = ["collection_id", "game_id"],
    indices = [Index("game_id")],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collection_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["game_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CollectionGameEntity(
    @ColumnInfo(name = "collection_id")
    val collectionId: Long,

    @ColumnInfo(name = "game_id")
    val gameId: Long,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis(),
)
