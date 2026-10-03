package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// The game the user inserted as a gaming column's UMD. One row per column (category id).
// game_id deliberately has no foreign key: a scan that replaces a game row would cascade the
// slot away, so the id is checked on read instead.
@Serializable
@Entity(
    tableName = "umd_slots",
    foreignKeys = [
        ForeignKey(
            entity        = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns  = ["column_id"],
            onDelete      = ForeignKey.CASCADE,
        )
    ],
)
data class UmdSlotEntity(
    @PrimaryKey
    @ColumnInfo(name = "column_id")
    val columnId: String,

    @ColumnInfo(name = "game_id")
    val gameId: Long,

    @ColumnInfo(name = "inserted_at")
    val insertedAt: Long,
)
