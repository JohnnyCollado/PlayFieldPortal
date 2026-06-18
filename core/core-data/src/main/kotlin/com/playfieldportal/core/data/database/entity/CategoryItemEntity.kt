package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import kotlinx.serialization.Serializable

// Junction table — links games/apps to MANUAL and SHORTCUT_GROUP categories
// SMART and PLATFORM categories populate themselves dynamically, not stored here
@Serializable
@Entity(
    tableName = "category_items",
    primaryKeys = ["category_id", "item_id"],
    foreignKeys = [
        ForeignKey(
            entity        = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns  = ["category_id"],
            onDelete      = ForeignKey.CASCADE,     // items auto-removed when category deleted
        )
    ],
    indices = [Index("category_id"), Index("item_id")],
)
data class CategoryItemEntity(
    @ColumnInfo(name = "category_id")
    val categoryId: String,

    // item_id is flexible — can reference a game ID, package name, or shortcut ID
    @ColumnInfo(name = "item_id")
    val itemId: String,

    @ColumnInfo(name = "item_type")
    val itemType: String,               // "game" | "app" | "shortcut"

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
)
