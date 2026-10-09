package com.playfieldportal.core.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.playfieldportal.core.data.saf.safScanStartDocId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Whether a granted folder can be read right now, and if not, whether the grant itself is gone. */
enum class FolderAccess {
    READABLE,
    /** The grant is held but the folder cannot be read yet (an SD card still mounting): retry, never report. */
    NOT_READY,
    /** No persisted grant covers the folder: only the user can pick it again. */
    NO_GRANT,
}

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

    /**
     * [treeUri]'s access, judged by the grant on its tree root (a library may be a subfolder
     * document riding its root's grant) and whether that folder was readable.
     */
    fun access(treeUri: String, persistedReadUris: Set<String>, rootReadable: Boolean): FolderAccess {
        val root = treeRootOf(treeUri) ?: return FolderAccess.NO_GRANT
        return when {
            root !in persistedReadUris -> FolderAccess.NO_GRANT
            rootReadable -> FolderAccess.READABLE
            else -> FolderAccess.NOT_READY
        }
    }

    // The tree URI a grant is persisted under, for a tree or a document-under-tree URI.
    private fun treeRootOf(uri: String): String? = runCatching {
        val parsed = Uri.parse(uri)
        DocumentsContract.buildTreeDocumentUri(parsed.authority, DocumentsContract.getTreeDocumentId(parsed)).toString()
    }.getOrNull()
}

/** [SafGrants.access] against the live grant list and the folder itself. Queries storage, so on IO. */
@Singleton
class SafFolderAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun of(treeUri: String): FolderAccess = withContext(Dispatchers.IO) {
        // One query on the folder's own document: it fails while the volume is not mounted.
        val readable = runCatching {
            val uri = Uri.parse(treeUri)
            val document = DocumentsContract.buildDocumentUriUsingTree(uri, safScanStartDocId(context, uri))
            context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
        SafGrants.access(treeUri, SafGrants.persistedReadUris(context.contentResolver), readable)
    }
}
