package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

// Fast lookup cache over the portable artwork library: one row per stored asset. The folder is
// the source of truth — this table is rebuildable at any time (Reload Library), so losing it is
// never data loss. Keyed by the stable ArtworkKey (rom/{platform}/{slug}, app/{pkg}, …) + kind.
@Entity(
    tableName = "artwork_index",
    primaryKeys = ["key", "kind"],
)
data class ArtworkIndexEntity(
    val key: String,

    // ArtworkKind name (ICON, HERO, BACKGROUND, LOGO, SCREENSHOT, …).
    val kind: String,

    // INTERNAL (filesDir path) or PORTABLE (content:// document URI in the user's tree).
    val location: String,

    // The loadable reference the UI columns carry — absolute path or content:// URI.
    @ColumnInfo(name = "doc_uri_or_path")
    val docUriOrPath: String,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)
