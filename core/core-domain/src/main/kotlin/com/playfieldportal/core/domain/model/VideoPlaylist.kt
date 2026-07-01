package com.playfieldportal.core.domain.model

/**
 * A user-created, ordered list of videos. Videos are referenced by [Video.id]; a playlist may span
 * any number of libraries. [videoCount] is computed for list rendering. Mirrors [Playlist].
 */
data class VideoPlaylist(
    val id: Long,
    val name: String,
    val videoCount: Int = 0,
)
