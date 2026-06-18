package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ROMs found during scan that could not be automatically matched or platform-assigned.
// Shown in Settings → Library → Unmatched ROMs for user resolution.
@Entity(
    tableName = "unmatched_roms",
    indices = [Index("file_path", unique = true)]
)
data class UnmatchedRomEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "file_path")
    val filePath: String,

    @ColumnInfo(name = "file_name")
    val fileName: String,

    // Populated for .chd/.img files where we know it's a disc but not which system
    @ColumnInfo(name = "detected_platform_id")
    val detectedPlatformId: String? = null,

    @ColumnInfo(name = "found_at")
    val foundAt: Long = System.currentTimeMillis(),

    // Set by user when they assign a platform — triggers game entry creation
    @ColumnInfo(name = "resolved_platform_id")
    val resolvedPlatformId: String? = null,
)
