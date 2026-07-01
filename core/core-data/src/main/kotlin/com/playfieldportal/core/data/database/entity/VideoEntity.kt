package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.playfieldportal.core.domain.model.Video

// One row per scanned video file. uri is a SAF document uri string. Cascade-deletes with its
// library so removing a library removes its videos; indexed by library_id for per-library queries.
@Entity(
    tableName = "videos",
    foreignKeys = [
        ForeignKey(
            entity = VideoLibraryEntity::class,
            parentColumns = ["id"],
            childColumns = ["library_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("library_id"), Index("uri")],
)
data class VideoEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "library_id")
    val libraryId: String,

    val uri: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    val title: String? = null,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long? = null,

    val width: Int? = null,
    val height: Int? = null,

    @ColumnInfo(name = "frame_rate")
    val frameRate: Float? = null,

    val codec: String? = null,

    @ColumnInfo(name = "mime_type")
    val mimeType: String? = null,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long? = null,

    @ColumnInfo(name = "date_added")
    val dateAdded: Long? = null,

    @ColumnInfo(name = "last_modified")
    val lastModified: Long? = null,

    @ColumnInfo(name = "relative_path")
    val relativePath: String? = null,

    @ColumnInfo(name = "thumbnail_uri")
    val thumbnailUri: String? = null,

    @ColumnInfo(name = "custom_thumbnail_uri")
    val customThumbnailUri: String? = null,

    @ColumnInfo(name = "resume_position_ms")
    val resumePositionMs: Long = 0,

    @ColumnInfo(name = "last_watched_at")
    val lastWatchedAt: Long? = null,

    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,
)

fun VideoEntity.toDomain() = Video(
    id                 = id,
    libraryId          = libraryId,
    uri                = uri,
    displayName        = displayName,
    title              = title,
    durationMs         = durationMs,
    width              = width,
    height             = height,
    frameRate          = frameRate,
    codec              = codec,
    mimeType           = mimeType,
    sizeBytes          = sizeBytes,
    dateAdded          = dateAdded,
    lastModified       = lastModified,
    relativePath       = relativePath,
    thumbnailUri       = thumbnailUri,
    customThumbnailUri = customThumbnailUri,
    resumePositionMs   = resumePositionMs,
    lastWatchedAt      = lastWatchedAt,
    isFavorite         = isFavorite,
)

fun Video.toEntity() = VideoEntity(
    id                 = id,
    libraryId          = libraryId,
    uri                = uri,
    displayName        = displayName,
    title              = title,
    durationMs         = durationMs,
    width              = width,
    height             = height,
    frameRate          = frameRate,
    codec              = codec,
    mimeType           = mimeType,
    sizeBytes          = sizeBytes,
    dateAdded          = dateAdded,
    lastModified       = lastModified,
    relativePath       = relativePath,
    thumbnailUri       = thumbnailUri,
    customThumbnailUri = customThumbnailUri,
    resumePositionMs   = resumePositionMs,
    lastWatchedAt      = lastWatchedAt,
    isFavorite         = isFavorite,
)
