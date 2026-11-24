package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// Per-app user overrides that must survive scans, launcher updates and package updates.
//   - customized: the user has taken manual control of this app's category placement, so
//     automatic classification no longer applies (its categories come from category_items,
//     even if that set is empty).
//   - isHidden: the app is hidden from all XMB categories.
//   - customLabel: a renamed shortcut label (null = use the real app label).
//   - gameOverride: the user marked (true) or unmarked (false) the app as a game.
@Serializable
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

    // The user's "Mark / Unmark as Game": true or false overrides the Play Store's category for the
    // App Drawer's Games tab; null follows it. Apart from any memory card (v59, 2026-11-24).
    @ColumnInfo(name = "game_override")
    val gameOverride: Boolean? = null,
)
