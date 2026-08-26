package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * RETIRED. "Remove from Library" previously wrote one of these so a folder rescan would not
 * re-import the still-on-disk file. That behavior was removed (see ADR-0001): removal now just
 * deletes the row and the next scan re-discovers the file, so nothing reads or writes tombstones
 * anymore. The entity and its `scan_tombstones` table are kept only to hold the Room schema
 * version steady for existing installs; no migration drops the table yet.
 */
@Entity(tableName = "scan_tombstones")
data class ScanTombstoneEntity(
    @PrimaryKey
    @ColumnInfo(name = "rom_path")
    val romPath: String,

    @ColumnInfo(name = "platform_id")
    val platformId: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)
