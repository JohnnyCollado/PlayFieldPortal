package com.playfieldportal.core.data.saf

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** One file found by [walkSafTree], plus the path of its directory relative to the scan root. */
data class SafFile(
    val child: SafChild,
    /** "" for a file directly under the root, otherwise "Sub/Folder". */
    val relativePath: String,
)

/**
 * The result of one tree walk: every file found, and a cheap [signature] summarising the tree.
 *
 * Two walks of an unchanged tree produce the same signature, so a caller holding the signature
 * from its last scan can skip the whole probe phase without opening a single file.
 */
data class SafTreeWalk(
    val files: List<SafFile>,
    val signature: String,
)

/**
 * Enumerates every file under [treeUri], cursor-only.
 *
 * This is the cheap half of a library scan: one [querySafChildren] cursor per directory, which
 * returns name/MIME/mtime/size for every entry in a single IPC round-trip. Nothing here opens a
 * file, decodes an image or touches MediaMetadataRetriever — that is the caller's probe phase,
 * and it is where all the real cost lives.
 *
 * Shared by MusicScanner, PhotoScanner and VideoScanner, which each grew their own copy of this
 * DFS and then drifted apart. The shared version carries the union of what they did:
 *  - **Iterative** DFS over document ids, so a deeply nested tree cannot blow the stack.
 *  - **[visitedDirs] cycle guard** — a provider whose directory graph loops (or that surfaces a
 *    folder under two parents) cannot make the walk run forever.
 *  - **[seenFiles] dedupe by uri** — one document reachable by two paths yields one row, not two
 *    duplicate rows racing to write the same thumbnail file.
 *  - **`.nomedia`** skips the folder's files *and* its whole subtree.
 *  - **[SafChild.isIgnoredDir]** prunes dotfile/cache directories before descending.
 *
 * Cancellable: [coroutineContext.ensureActive] is checked per directory and per entry.
 *
 * @param recursive false stops at the root's own files. Music folders are always recursive —
 *   `music_folders` has no `scan_recursively` column, by design — while photo and video
 *   libraries pass their own flag.
 */
suspend fun Context.walkSafTree(treeUri: Uri, recursive: Boolean): SafTreeWalk {
    val files = mutableListOf<SafFile>()
    val visitedDirs = HashSet<String>()
    val seenFiles = HashSet<String>()

    val rootDocId = safScanStartDocId(this, treeUri)
    visitedDirs.add(rootDocId)

    val stack = ArrayDeque<Pair<String, String>>()   // documentId to relative path
    stack.addLast(rootDocId to "")
    while (stack.isNotEmpty()) {
        coroutineContext.ensureActive()
        val (dirDocId, relPath) = stack.removeLast()
        val children = contentResolver.querySafChildren(treeUri, dirDocId)
        if (children.hasNoMediaMarker()) continue
        for (child in children) {
            coroutineContext.ensureActive()
            if (child.isDirectory) {
                if (!recursive) continue
                if (child.isIgnoredDir()) continue
                if (!visitedDirs.add(child.documentId)) continue
                stack.addLast(
                    child.documentId to if (relPath.isEmpty()) child.name else "$relPath/${child.name}"
                )
            } else if (seenFiles.add(child.uri.toString())) {
                files.add(SafFile(child, relPath))
            }
        }
    }
    return SafTreeWalk(files, safTreeSignature(files))
}

/**
 * A cheap fingerprint of an enumerated tree: `count:totalBytes:newestMtime`.
 *
 * Every input is already in the directory cursor, so the signature costs nothing beyond the walk
 * itself. Size is summed rather than relying on mtime alone because a good many document
 * providers — USB/MTP and some SD cards especially — report no usable `lastModified` at all
 * (see [querySafChildren], which maps a zero mtime to null). A tree whose files all report a null
 * mtime still changes its signature the moment a file is added, removed or resized.
 *
 * It is deliberately not a hash of every name: this detects "did anything change", not "what
 * changed". A scan follows when it differs, and the scan is what reconciles per file.
 */
fun safTreeSignature(files: List<SafFile>): String {
    var totalBytes = 0L
    var newestMtime = 0L
    for (file in files) {
        totalBytes += file.child.sizeBytes ?: 0L
        val mtime = file.child.lastModified ?: 0L
        if (mtime > newestMtime) newestMtime = mtime
    }
    return "${files.size}:$totalBytes:$newestMtime"
}
