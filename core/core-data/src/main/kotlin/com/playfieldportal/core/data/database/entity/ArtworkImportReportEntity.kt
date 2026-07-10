package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// A completed (or aborted) artwork import run, viewable later from the Import Report screen.
// The detailed counts/errors live in summary_json (kotlinx-serialization; parsed defensively)
// so the schema never has to migrate when the report format grows.
@Entity(tableName = "artwork_import_reports")
data class ArtworkImportReportEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Source label, e.g. "ES-DE".
    val source: String,

    @ColumnInfo(name = "started_at")
    val startedAt: Long,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,

    @ColumnInfo(name = "summary_json")
    val summaryJson: String,
)
