package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// A list's own sort mode. No row means the list follows the global setting.
@Serializable
@Entity(tableName = "list_settings")
data class ListSettingEntity(
    @PrimaryKey
    @ColumnInfo(name = "list_key")
    val listKey: String,

    @ColumnInfo(name = "sort_mode")
    val sortMode: String,               // ListSortMode.name
)
