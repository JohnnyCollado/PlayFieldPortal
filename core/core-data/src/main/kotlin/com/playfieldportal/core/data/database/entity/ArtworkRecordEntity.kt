package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// One artwork asset in the portable media library (layout v2): who it belongs to, where it
// lives, who supplied it, and whether the user pinned it. This table is the app's fast map
// over the folder — the folder stays the source of truth and Relink/Scan can rebuild rows,
// so losing them is never data loss. Replaces the v25 artwork_index.
@Entity(
    tableName = "artwork_records",
    indices = [
        Index("game_id", "artwork_type", unique = true),
        // Collision checks at write time (FAT is case-insensitive; enforced in code via NOCASE).
        Index("platform_id", "artwork_type", "portable_name"),
    ],
)
data class ArtworkRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "game_id")
    val gameId: Long,

    @ColumnInfo(name = "platform_id")
    val platformId: String,

    // ArtworkKind name (ICON, HERO, BACKGROUND, LOGO, SCREENSHOT, …, MANUAL).
    @ColumnInfo(name = "artwork_type")
    val artworkType: String,

    // Filename stem inside the media dir ("Final Fantasy X (USA)").
    @ColumnInfo(name = "portable_name")
    val portableName: String,

    // Library-relative path ("ps2/covers/Final Fantasy X (USA).png").
    @ColumnInfo(name = "relative_path")
    val relativePath: String,

    // The loadable reference the UI carries — content:// document URI (or internal path).
    @ColumnInfo(name = "document_uri")
    val documentUri: String,

    // Who supplied the bytes: "import-esde", "screenscraper", "sgdb", "user", …
    val source: String,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long = 0,

    // Filled lazily by background Verify/Scan — never inline on the write path (a checksum
    // would force reading every byte and defeat the move/kernel-copy optimizations).
    val width: Int? = null,
    val height: Int? = null,
    val checksum: String? = null,

    // User picked this file explicitly; scrapes and imports never overwrite it.
    @ColumnInfo(name = "user_assigned")
    val userAssigned: Boolean = false,

    val locked: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)
