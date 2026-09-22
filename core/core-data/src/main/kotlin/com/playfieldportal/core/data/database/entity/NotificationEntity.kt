package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A settled outcome in the notification panel's EARLIER section.
 *
 * Enums are stored as their names rather than ordinals so reordering
 * `NotificationKind`/`NotificationSeverity` can never silently re-label existing rows, and both
 * read back through `fromName`, which degrades an unrecognised value instead of throwing.
 *
 * `source_key` is uniquely indexed: a post carrying one replaces the row that already holds it.
 * SQLite treats NULLs as distinct in a unique index, so keyless rows still append freely.
 */
@Entity(
    tableName = "notifications",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["read_at"]),
        Index(value = ["kind"]),
        Index(value = ["source_key"], unique = true),
    ],
)
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "kind")
    val kind: String,

    @ColumnInfo(name = "severity")
    val severity: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "body")
    val body: String? = null,

    @ColumnInfo(name = "source_key")
    val sourceKey: String? = null,

    @ColumnInfo(name = "action_type")
    val actionType: String? = null,

    @ColumnInfo(name = "action_arg")
    val actionArg: String? = null,

    /** Opaque JSON. Nothing writes it yet; it is the seam an RSS enclosure will use (plan §8). */
    @ColumnInfo(name = "payload")
    val payload: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "read_at")
    val readAt: Long? = null,
)
