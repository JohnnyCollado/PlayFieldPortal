package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-deleted scanned game. "Remove from Library" only deletes the DB row — the ROM file stays
 * on disk — so without a tombstone the next folder scan would just re-import it. Scanners must
 * skip any file whose rom_path has a tombstone. Rows are cleared when the user re-scans after
 * explicitly clearing hidden/removed items, or when the platform's card is removed.
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
