package com.playfieldportal.core.data.saf

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import timber.log.Timber

// One row of a SAF directory's child listing — everything a scanner needs, from a single cursor.
// DocumentFile issues a separate ContentResolver IPC query per property per file (name, type,
// lastModified, length, isDirectory ≈ 6 round-trips each); this fetches a whole directory in one
// query. Shared by the library scanners and the artwork importer.
data class SafChild(
    val documentId: String,
    val uri: Uri,
    val name: String,
    val mime: String?,
    val isDirectory: Boolean,
    val lastModified: Long?,
    val sizeBytes: Long?,
)

// A directory carrying a `.nomedia` marker must not be indexed — its own files and its whole
// subtree are skipped (the standard Android convention for "don't scan this folder").
fun List<SafChild>.hasNoMediaMarker(): Boolean =
    any { !it.isDirectory && it.name.equals(".nomedia", ignoreCase = true) }

// Directories pruned immediately without entering: dotfiles/hidden dirs (`.thumbnails`, `.trash`,
// the PFP thumbnail cache, Android cache dirs, etc.). Cheaper than recursing then discarding.
fun SafChild.isIgnoredDir(): Boolean = isDirectory && name.startsWith(".")

// The DFS start for a library uri: a plain tree uri starts at its tree document; a
// document-under-tree uri (an auto-detected subfolder of a granted media root) starts at that
// document, so the scan covers just the subfolder while riding the root's grant.
fun safScanStartDocId(context: android.content.Context, uri: Uri): String =
    if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri)
    else DocumentsContract.getTreeDocumentId(uri)

// One ContentResolver query per directory. Child document ids are resolved through
// buildChildDocumentsUriUsingTree, so every returned uri stays scoped to the tree permission the
// user granted — a scan can never read outside the folder they picked. Malformed provider rows
// (null id/name) are skipped and a failing provider yields an empty list instead of a crash.
fun ContentResolver.querySafChildren(treeUri: Uri, parentDocumentId: String): List<SafChild> {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE,
    )
    val out = mutableListOf<SafChild>()
    runCatching {
        query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                out.add(
                    SafChild(
                        documentId   = docId,
                        uri          = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                        name         = name,
                        mime         = mime,
                        isDirectory  = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        lastModified = if (c.isNull(3)) null else c.getLong(3).takeIf { it > 0 },
                        sizeBytes    = if (c.isNull(4)) null else c.getLong(4).takeIf { it > 0 },
                    )
                )
            }
        }
    }.onFailure { Timber.w(it, "Could not list children of $parentDocumentId") }
    return out
}
