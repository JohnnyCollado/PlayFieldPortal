package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * One artwork file that Scan & Relink walked but could not tie to any game (C22 task T1).
 *
 * **Why a table and not a counter.** Relink used to do `orphans++` and throw the filename away,
 * so "47 unmatched" was all anyone ever knew — there was no way to show a user *which* files, and
 * therefore no way to let them resolve one by hand. These rows are what the orphan picker reads.
 *
 * **Why a table and not the import report's `summary_json`.** A JSON blob cannot be queried per
 * platform, and a scoped relink has to replace one platform's rows while leaving every other
 * platform's alone. That is a delete-where, which a blob cannot express. (`artwork_import_reports`
 * is also only ever written by the import executor — a relink writes no report row at all.)
 *
 * Like `artwork_records`, this is derived state over a user-owned folder: losing it costs one
 * relink, never data. Nothing here is authoritative.
 */
@Entity(
    tableName = "artwork_orphan_files",
    primaryKeys = ["platform_id", "artwork_type", "file_name"],
    indices = [Index("platform_id")],
)
data class ArtworkOrphanFileEntity(

    @ColumnInfo(name = "platform_id")
    val platformId: String,

    /** ArtworkKind name the containing media dir maps to (ICON, BOX_ART, SCREENSHOT, …). */
    @ColumnInfo(name = "artwork_type")
    val artworkType: String,

    @ColumnInfo(name = "file_name")
    val fileName: String,

    /** [fileName] without its extension — what the picker pre-fills its search field with. */
    val stem: String,

    /** The loadable reference, so the picker can show a thumbnail without walking the tree again. */
    @ColumnInfo(name = "document_uri")
    val documentUri: String,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,

    /** When the walk that recorded this row ran. Rows are replaced wholesale, never merged. */
    @ColumnInfo(name = "seen_at")
    val seenAt: Long = System.currentTimeMillis(),
)
