package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// User-configured ROM scan folders and manual game sources
@Entity(tableName = "library_sources")
data class LibrarySourceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Absolute folder path — e.g. /storage/emulated/0/ROMs/PS2
    val path: String,

    // Human label shown in Settings → Library
    val label: String,

    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,

    @ColumnInfo(name = "last_scanned_at")
    val lastScannedAt: Long? = null,

    @ColumnInfo(name = "game_count")
    val gameCount: Int = 0,

    // Non-null when this source is tied to a specific platform.
    // All ROMs found here are assigned to this platform regardless of extension.
    @ColumnInfo(name = "platform_id")
    val platformId: String? = null,
)
