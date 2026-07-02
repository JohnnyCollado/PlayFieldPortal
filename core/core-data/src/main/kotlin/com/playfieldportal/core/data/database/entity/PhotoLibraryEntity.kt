package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.playfieldportal.core.domain.model.PhotoLibrary

// One row per user-added SAF photo library (Album). id is a generated UUID; tree_uri is the
// persisted ACTION_OPEN_DOCUMENT_TREE uri. photo_count / last_scanned_at mirror the video-library
// pattern so the album list stays reactive with counts without an extra join.
@Entity(tableName = "photo_libraries")
data class PhotoLibraryEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "tree_uri")
    val treeUri: String,

    val enabled: Boolean = true,

    @ColumnInfo(name = "scan_recursively")
    val scanRecursively: Boolean = true,

    @ColumnInfo(name = "photo_count")
    val photoCount: Int = 0,

    @ColumnInfo(name = "last_scanned_at")
    val lastScannedAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

fun PhotoLibraryEntity.toDomain() = PhotoLibrary(
    id              = id,
    displayName     = displayName,
    treeUri         = treeUri,
    enabled         = enabled,
    scanRecursively = scanRecursively,
    photoCount      = photoCount,
    lastScannedAt   = lastScannedAt,
    createdAt       = createdAt,
    updatedAt       = updatedAt,
)

fun PhotoLibrary.toEntity() = PhotoLibraryEntity(
    id              = id,
    displayName     = displayName,
    treeUri         = treeUri,
    enabled         = enabled,
    scanRecursively = scanRecursively,
    photoCount      = photoCount,
    lastScannedAt   = lastScannedAt,
    createdAt       = createdAt,
    updatedAt       = updatedAt,
)
