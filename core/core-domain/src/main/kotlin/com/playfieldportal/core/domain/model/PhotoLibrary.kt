package com.playfieldportal.core.domain.model

/**
 * A user-added photo source: a SAF document-tree URI the user granted read access to. Mirrors
 * [VideoLibrary] (SAF-only, manual scan). Behaves like an Album under the Photo category; photos
 * are discovered by a manual quick/deep scan and stored as [Photo] rows.
 */
data class PhotoLibrary(
    val id: String,
    val displayName: String,
    val treeUri: String,
    val enabled: Boolean = true,
    val scanRecursively: Boolean = true,
    val photoCount: Int = 0,
    val lastScannedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
