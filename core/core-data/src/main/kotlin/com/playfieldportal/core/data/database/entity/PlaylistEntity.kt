package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.playfieldportal.core.domain.model.Playlist

// A user-created music playlist. Ordered among siblings by sort_order then created_at.
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
)

// Membership of a track in a playlist. position keeps the user's manual ordering; a track may
// appear in many playlists. Rows cascade-delete with their playlist. Tracks are referenced by id
// (TEXT) — there is no FK to music_tracks so a track removed by a re-scan simply drops out of any
// join (the orphan row is cleaned up opportunistically by the repository).
@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlist_id", "track_id"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlist_id"), Index("track_id")],
)
data class PlaylistTrackEntity(
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,

    @ColumnInfo(name = "track_id")
    val trackId: String,

    val position: Int,

    @ColumnInfo(name = "added_at")
    val addedAt: Long,
)

fun PlaylistEntity.toDomain(trackCount: Int = 0) = Playlist(
    id = id,
    name = name,
    trackCount = trackCount,
)
