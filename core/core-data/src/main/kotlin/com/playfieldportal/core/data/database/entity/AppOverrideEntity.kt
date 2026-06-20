package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// Per-app user overrides that must survive scans, launcher updates and package updates.
//   - customized: the user has taken manual control of this app's category placement, so
//     automatic classification no longer applies (its categories come from category_items,
//     even if that set is empty).
//   - isHidden: the app is hidden from all XMB categories.
//   - customLabel: a renamed shortcut label (null = use the real app label).
@Entity(tableName = "app_overrides")
data class AppOverrideEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "custom_label")
    val customLabel: String? = null,

    @ColumnInfo(name = "is_hidden")
    val isHidden: Boolean = false,

    val customized: Boolean = false,
)
