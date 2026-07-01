package com.playfieldportal.core.domain.model

/**
 * A user-added video source: a SAF document-tree URI the user granted read access to. Mirrors
 * [MusicFolder] (SAF-only, manual scan) and the Memory Card model (one folder + scan settings).
 * Videos are discovered by a manual quick/deep scan and stored as [Video] rows.
 */
data class VideoLibrary(
    val id: String,
    val displayName: String,
    val treeUri: String,
    /** Optional custom artwork (file:// or content:// uri) shown on the library's XMB card. */
    val artworkUri: String? = null,
    val enabled: Boolean = true,
    val scanRecursively: Boolean = true,
    val videoCount: Int = 0,
    val lastScannedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
