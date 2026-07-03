package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import com.playfieldportal.core.domain.model.MusicFolder

// One row per user-added SAF music folder. id is a generated UUID; treeUri is the persisted
// ACTION_OPEN_DOCUMENT_TREE uri. track_count / last_scanned_at mirror the Memory Card pattern so
// the folder list stays reactive with counts without an extra join.
@Serializable
@Entity(tableName = "music_folders")
data class MusicFolderEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "tree_uri")
    val treeUri: String,

    val enabled: Boolean = true,

    @ColumnInfo(name = "track_count")
    val trackCount: Int = 0,

    @ColumnInfo(name = "last_scanned_at")
    val lastScannedAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

fun MusicFolderEntity.toDomain() = MusicFolder(
    id            = id,
    displayName   = displayName,
    treeUri       = treeUri,
    enabled       = enabled,
    trackCount    = trackCount,
    lastScannedAt = lastScannedAt,
    createdAt     = createdAt,
    updatedAt     = updatedAt,
)

fun MusicFolder.toEntity() = MusicFolderEntity(
    id            = id,
    displayName   = displayName,
    treeUri       = treeUri,
    enabled       = enabled,
    trackCount    = trackCount,
    lastScannedAt = lastScannedAt,
    createdAt     = createdAt,
    updatedAt     = updatedAt,
)
