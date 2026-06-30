package com.playfieldportal.core.domain.model

/**
 * A user-created, ordered list of music tracks. Tracks are referenced by [MusicTrack.id]; a
 * playlist may span any number of scanned folders. [trackCount] is computed for list rendering.
 */
data class Playlist(
    val id: Long,
    val name: String,
    val trackCount: Int = 0,
)
