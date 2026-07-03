package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import com.playfieldportal.core.domain.model.Photo

// One row per scanned photo file. uri is a SAF document uri string. Cascade-deletes with its
// library so removing an album removes its photos; indexed by library_id for per-album queries.
@Serializable
@Entity(
    tableName = "photos",
    foreignKeys = [
        ForeignKey(
            entity = PhotoLibraryEntity::class,
            parentColumns = ["id"],
            childColumns = ["library_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("library_id"), Index("uri")],
)
data class PhotoEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "library_id")
    val libraryId: String,

    val uri: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    val width: Int? = null,
    val height: Int? = null,

    @ColumnInfo(name = "date_taken")
    val dateTaken: Long? = null,

    @ColumnInfo(name = "last_modified")
    val lastModified: Long? = null,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long? = null,

    @ColumnInfo(name = "mime_type")
    val mimeType: String? = null,

    @ColumnInfo(name = "relative_path")
    val relativePath: String? = null,

    @ColumnInfo(name = "thumbnail_uri")
    val thumbnailUri: String? = null,

    @ColumnInfo(name = "date_added")
    val dateAdded: Long? = null,
)

fun PhotoEntity.toDomain() = Photo(
    id           = id,
    libraryId    = libraryId,
    uri          = uri,
    displayName  = displayName,
    width        = width,
    height       = height,
    dateTaken    = dateTaken,
    lastModified = lastModified,
    sizeBytes    = sizeBytes,
    mimeType     = mimeType,
    relativePath = relativePath,
    thumbnailUri = thumbnailUri,
    dateAdded    = dateAdded,
)

fun Photo.toEntity() = PhotoEntity(
    id           = id,
    libraryId    = libraryId,
    uri          = uri,
    displayName  = displayName,
    width        = width,
    height       = height,
    dateTaken    = dateTaken,
    lastModified = lastModified,
    sizeBytes    = sizeBytes,
    mimeType     = mimeType,
    relativePath = relativePath,
    thumbnailUri = thumbnailUri,
    dateAdded    = dateAdded,
)
