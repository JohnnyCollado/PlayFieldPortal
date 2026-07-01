package com.playfieldportal.core.domain.model

/**
 * A single scanned video file. [uri] is a SAF document URI string (never a raw file path) so the
 * file can be opened by the built-in player (or, later, an external player) with a grantable read
 * permission. Mirrors [MusicTrack], extended with video metadata and resume-position state.
 */
data class Video(
    val id: String,
    val libraryId: String,
    val uri: String,
    val displayName: String,
    /** User-set display title override; falls back to [displayName]. */
    val title: String? = null,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
    val codec: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val dateAdded: Long? = null,
    val lastModified: Long? = null,
    val relativePath: String? = null,
    /** file:// uri of the generated thumbnail (a frame grab cached during scan), or null. */
    val thumbnailUri: String? = null,
    /** file:// or content:// uri of a user-chosen custom thumbnail; takes priority when set. */
    val customThumbnailUri: String? = null,
    /** Last playback position in ms; 0 = start / fully watched-and-reset. */
    val resumePositionMs: Long = 0,
    val lastWatchedAt: Long? = null,
) {
    /** Best label for display: user title when set, else the file name. */
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: displayName

    /** The thumbnail to show: user custom art first, then the generated frame grab. */
    val effectiveThumbnailUri: String? get() = customThumbnailUri?.takeIf { it.isNotBlank() } ?: thumbnailUri

    /** "1920×1080"-style label, or null when resolution is unknown. */
    val resolutionLabel: String? get() =
        if (width != null && height != null && width > 0 && height > 0) "${width}×${height}" else null
}
