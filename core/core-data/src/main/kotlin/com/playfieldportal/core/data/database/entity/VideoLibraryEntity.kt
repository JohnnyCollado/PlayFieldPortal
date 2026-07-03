package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import com.playfieldportal.core.domain.model.VideoLibrary

// One row per user-added SAF video library. id is a generated UUID; tree_uri is the persisted
// ACTION_OPEN_DOCUMENT_TREE uri. video_count / last_scanned_at mirror the MusicFolder/Memory Card
// pattern so the library list stays reactive with counts without an extra join.
@Serializable
@Entity(tableName = "video_libraries")
data class VideoLibraryEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "tree_uri")
    val treeUri: String,

    @ColumnInfo(name = "artwork_uri")
    val artworkUri: String? = null,

    val enabled: Boolean = true,

    @ColumnInfo(name = "scan_recursively")
    val scanRecursively: Boolean = true,

    @ColumnInfo(name = "video_count")
    val videoCount: Int = 0,

    @ColumnInfo(name = "last_scanned_at")
    val lastScannedAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

fun VideoLibraryEntity.toDomain() = VideoLibrary(
    id              = id,
    displayName     = displayName,
    treeUri         = treeUri,
    artworkUri      = artworkUri,
    enabled         = enabled,
    scanRecursively = scanRecursively,
    videoCount      = videoCount,
    lastScannedAt   = lastScannedAt,
    createdAt       = createdAt,
    updatedAt       = updatedAt,
)

fun VideoLibrary.toEntity() = VideoLibraryEntity(
    id              = id,
    displayName     = displayName,
    treeUri         = treeUri,
    artworkUri      = artworkUri,
    enabled         = enabled,
    scanRecursively = scanRecursively,
    videoCount      = videoCount,
    lastScannedAt   = lastScannedAt,
    createdAt       = createdAt,
    updatedAt       = updatedAt,
)
