package com.playfieldportal.core.domain.model

/**
 * A user-added music source: a SAF document-tree URI the user granted read access to. Music is
 * discovered by a manual scan (never background polling) and stored as [MusicTrack] rows.
 */
data class MusicFolder(
    val id: String,
    val displayName: String,
    val treeUri: String,
    val enabled: Boolean = true,
    val trackCount: Int = 0,
    val lastScannedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
