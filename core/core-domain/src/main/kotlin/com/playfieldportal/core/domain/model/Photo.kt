package com.playfieldportal.core.domain.model

/**
 * A single scanned photo file. [uri] is a SAF document URI string (never a raw file path) so the
 * file can be opened with a grantable read permission. Mirrors [Video], trimmed to the metadata a
 * PSP-style photo list needs: resolution, date taken, and a cached thumbnail.
 */
data class Photo(
    val id: String,
    val libraryId: String,
    val uri: String,
    val displayName: String,
    val width: Int? = null,
    val height: Int? = null,
    /** EXIF capture time (epoch ms), when the file carries it. */
    val dateTaken: Long? = null,
    val lastModified: Long? = null,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val relativePath: String? = null,
    /** file:// uri of the generated thumbnail (cached during scan), or null. */
    val thumbnailUri: String? = null,
    val dateAdded: Long? = null,
) {
    /** "4032×3024"-style label, or null when resolution is unknown. */
    val resolutionLabel: String? get() =
        if (width != null && height != null && width > 0 && height > 0) "${width}×${height}" else null

    /** Best available date for display: EXIF capture time, else the file's modified time. */
    val displayDateMs: Long? get() = dateTaken ?: lastModified
}
