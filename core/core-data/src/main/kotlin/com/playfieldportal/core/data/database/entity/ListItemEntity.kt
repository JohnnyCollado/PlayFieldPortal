package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable

// One item's place in one XMB list: its slot in the list's Custom order and, for game rows,
// its Pin to Top. Keyed by strings (see ListKeys) so a list with no junction table of its own —
// a Memory Card, All Games, a column's root — can still be arranged. No foreign keys: a key
// whose item is gone is ignored on read and removed with its owner.
@Serializable
@Entity(
    tableName = "list_items",
    primaryKeys = ["list_key", "item_key"],
    indices = [Index("item_key")],
)
data class ListItemEntity(
    @ColumnInfo(name = "list_key")
    val listKey: String,

    @ColumnInfo(name = "item_key")
    val itemKey: String,

    // Slot in the list's Custom order; null when the item is only pinned.
    val position: Int? = null,

    val pinned: Boolean = false,
)
