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
        // Position-aware: multi-asset kinds (screenshots, videos) hold several rows per slot,
        // each at its own sort_order. Single-art kinds only ever occupy position 0.
        Index("game_id", "artwork_type", "sort_order", unique = true),
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

    // Position within the slot. 0 is the primary (and the only position single-art kinds use);
    // screenshots and videos append at 1, 2, … The ordinal is mirrored in the filename and the
    // portable name (ArtworkFileNaming.withOrdinal) so Relink/Scan can rebuild this from disk.
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

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

    // ── Provenance (Studio pass 2) ──────────────────────────────────────────────
    // The remote URL these bytes came from. "Reset to Scraped Default" re-downloads it, and it
    // is the re-fetch source for browse history instead of stockpiling every past download.
    @ColumnInfo(name = "origin_url")
    val originUrl: String? = null,

    // The provider label the Studio showed ("ScreenScraper", "SteamGridDB", …) — file-info panel.
    @ColumnInfo(name = "provider")
    val provider: String? = null,

    // The provider's own id for this asset (SteamGridDB art id, ScreenScraper media key, …).
    // With origin_url and checksum it is how a duplicate apply is recognized before it lands.
    @ColumnInfo(name = "provider_asset_id")
    val providerAssetId: String? = null,

    // ── One-previous version retention (Studio pass 2, "Restore Previous") ──────
    // When a save overwrites a stable portable name, the outgoing file is copied to pfp/versions/
    // first; these point at it so a single undo is possible without stockpiling N copies.
    @ColumnInfo(name = "prev_document_uri")
    val prevDocumentUri: String? = null,

    @ColumnInfo(name = "prev_relative_path")
    val prevRelativePath: String? = null,

    @ColumnInfo(name = "prev_size_bytes")
    val prevSizeBytes: Long = 0,

    // ── Baked crop (Studio pass 2 editor) ──────────────────────────────────────
    // Normalized crop rect applied to the untouched original when baking the displayed file,
    // as "left,top,right,bottom" in 0..1. Null = no crop (file is the original framing).
    @ColumnInfo(name = "crop_rect")
    val cropRect: String? = null,

    // An untouched pre-crop copy exists under pfp/originals/ — lets the editor re-crop losslessly.
    @ColumnInfo(name = "has_original")
    val hasOriginal: Boolean = false,

    // The crop profile this asset was framed with (registry key; null = platform default).
    // Written by the Phase 6 crop registry; carried here so a per-game override survives a rescan.
    @ColumnInfo(name = "crop_profile_key")
    val cropProfileKey: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)
