package com.playfieldportal.core.data.repository

import android.content.ContentResolver

enum class FolderLinkStatus {
    /** A live persisted read grant exists for the stored tree URI. */
    LINKED,
    /** The tree URI is stored but the OS grant is gone — needs re-linking. */
    ACCESS_LOST,
}

/**
 * Shared status rules for persisted SAF grants, used by every "Root Access" section (Library ROM
 * roots, Music/Video/Photo roots, Backup folder). After a wipe/reinstall all grants are gone, so
 * stored roots report [FolderLinkStatus.ACCESS_LOST] until the user re-links them (one tap each —
 * the picker opens pre-pointed at the saved folder).
 */
object SafGrants {

    /** Read-permission URIs currently persisted to this app, as strings for exact comparison. */
    fun persistedReadUris(contentResolver: ContentResolver): Set<String> =
        contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()

    /** Pure status rule, factored out for unit testing. */
    fun linkStatus(treeUri: String, persistedReadUris: Set<String>): FolderLinkStatus =
        if (treeUri in persistedReadUris) FolderLinkStatus.LINKED else FolderLinkStatus.ACCESS_LOST
}
