package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.playfieldportal.core.domain.model.GameCollection

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
)

fun CollectionEntity.toDomain(gameCount: Int = 0) = GameCollection(
    id        = id,
    name      = name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    sortOrder = sortOrder,
    gameCount = gameCount,
)

fun GameCollection.toEntity() = CollectionEntity(
    id        = id,
    name      = name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    sortOrder = sortOrder,
)
