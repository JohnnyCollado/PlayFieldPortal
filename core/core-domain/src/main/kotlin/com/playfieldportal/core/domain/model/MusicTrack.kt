package com.playfieldportal.core.domain.model

/**
 * A single scanned audio file. [uri] is a SAF document URI string (never a raw file path) so the
 * track can be launched via an external player with a grantable read permission.
 */
data class MusicTrack(
    val id: String,
    val folderId: String,
    val uri: String,
    val displayName: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null,
    val trackNumber: Int? = null,
    val relativePath: String? = null,
) {
    /** Best label for display: real title when scanned, else the file name. */
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: displayName
}
