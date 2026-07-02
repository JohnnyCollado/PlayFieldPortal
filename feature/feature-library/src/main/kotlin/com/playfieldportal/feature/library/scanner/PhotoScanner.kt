package com.playfieldportal.feature.library.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.playfieldportal.core.data.photo.PhotoFileFilter
import com.playfieldportal.core.domain.model.Photo
import com.playfieldportal.core.domain.model.PhotoLibrary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

// Longest edge of a generated list thumbnail, in px. Small enough to decode fast and cache cheap,
// large enough for the 60×40 list tile and the flyout.
private const val THUMB_MAX_DIM = 320

// Concurrent per-file probes (decode bounds + EXIF + thumbnail). Bounded so a folder full of huge
// images can't exhaust memory or starve the device — decodes are subsampled, so four in flight is
// a few MB at worst.
private const val SCAN_PARALLELISM = 4

sealed interface PhotoScanResult {
    data class Progress(val libraryName: String, val filesSeen: Int, val photosFound: Int) : PhotoScanResult
    data class Complete(val libraryId: String, val photos: List<Photo>) : PhotoScanResult
    data class Error(val libraryId: String, val message: String) : PhotoScanResult
}

/**
 * Walks a [PhotoLibrary]'s SAF document tree and emits the image files it finds. Always
 * user-initiated (never background/observer-driven). Runs on [Dispatchers.IO], skips
 * unreadable/corrupt files with a log rather than crashing, and is cancellable via
 * [coroutineContext.ensureActive].
 *
 * Directory listing goes through [DocumentsContract] directly — one child query per directory
 * returns name/MIME/mtime/size for every entry in a single cursor. (DocumentFile would issue a
 * separate ContentResolver query per property per file, which made scans of photo folders — many
 * small files — take ~6 IPC round-trips each before any image work.)
 *
 * Two modes (both add new files and drop files that no longer exist — stale entries are always
 * pruned):
 *  - **Quick** ([deep] = false): for files whose `lastModified` is unchanged, the existing row is
 *    reused verbatim (metadata + thumbnail) — no per-file decode cost. Only new/modified files are
 *    probed.
 *  - **Deep** ([deep] = true): every file's metadata is re-read and any missing thumbnail is
 *    regenerated; an existing valid thumbnail is preserved keyed by uri.
 */
@Singleton
class PhotoScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun scan(
        library: PhotoLibrary,
        deep: Boolean,
        existing: List<Photo>,
    ): Flow<PhotoScanResult> = channelFlow {
        val treeUri = runCatching { Uri.parse(library.treeUri) }.getOrNull()
        val root = treeUri?.let { DocumentFile.fromTreeUri(context, it) }
        if (treeUri == null || root == null || !root.canRead()) {
            send(PhotoScanResult.Error(library.id, "Permission lost, re-select folder."))
            return@channelFlow
        }

        val startMs = System.currentTimeMillis()
        Timber.i("Photo scan started (${if (deep) "deep" else "quick"}): \"${library.displayName}\"")
        val byUri = existing.associateBy { it.uri }

        // ── Phase 1: enumerate candidate files — cursor-only, one query per directory. ─────
        // Iterative DFS over document IDs so deeply nested trees don't blow the stack; the
        // visited-set stops a cyclic/self-referencing provider from looping the scan forever.
        val files = mutableListOf<Pair<SafChild, String>>()   // child + its relative path
        val visitedDirs = HashSet<String>()
        // Dedupe files by uri too: a provider surfacing one document under two parents would
        // otherwise produce duplicate rows and two concurrent writers on one thumbnail file.
        val seenFiles = HashSet<String>()
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        visitedDirs.add(rootDocId)
        val stack = ArrayDeque<Pair<String, String>>()   // documentId to relative path
        stack.addLast(rootDocId to "")
        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (dirDocId, relPath) = stack.removeLast()
            for (child in context.contentResolver.querySafChildren(treeUri, dirDocId)) {
                if (child.isDirectory) {
                    if (!library.scanRecursively) continue
                    if (!visitedDirs.add(child.documentId)) continue
                    stack.addLast(child.documentId to if (relPath.isEmpty()) child.name else "$relPath/${child.name}")
                } else {
                    if (seenFiles.add(child.uri.toString())) files.add(child to relPath)
                }
            }
        }
        send(PhotoScanResult.Progress(library.displayName, files.size, 0))

        // ── Phase 2: probe files with bounded parallelism. ─────────────────────────────────
        // Quick-scan hits on unchanged files return without any I/O; new/changed files decode
        // bounds + EXIF + thumbnail concurrently, capped at SCAN_PARALLELISM in-flight.
        val processed = AtomicInteger(0)
        val found = AtomicInteger(0)
        val semaphore = Semaphore(SCAN_PARALLELISM)
        val photos = coroutineScope {
            files.map { (child, relPath) ->
                async {
                    semaphore.withPermit {
                        val photo = runCatching { child.toPhotoOrNull(library.id, relPath, deep, byUri) }
                            .getOrElse { e ->
                                if (e is CancellationException) throw e
                                Timber.w(e, "Skipping unreadable file ${child.uri}")
                                null
                            }
                        if (photo != null) found.incrementAndGet()
                        val done = processed.incrementAndGet()
                        if (done % 25 == 0) {
                            trySend(PhotoScanResult.Progress(library.displayName, done, found.get()))
                        }
                        photo
                    }
                }
            }.awaitAll().filterNotNull()
        }

        val took = System.currentTimeMillis() - startMs
        Timber.i("Photo scan complete: \"${library.displayName}\" — ${photos.size} photos from ${files.size} files in ${took}ms")
        send(PhotoScanResult.Complete(library.id, photos))
    }.flowOn(Dispatchers.IO)

    private fun SafChild.toPhotoOrNull(
        libraryId: String,
        relPath: String,
        deep: Boolean,
        existingByUri: Map<String, Photo>,
    ): Photo? {
        if (!PhotoFileFilter.isPhoto(name, mime)) return null

        val uriStr = uri.toString()
        val prior = existingByUri[uriStr]

        // Quick scan: reuse an unchanged file's row wholesale — no decode, no extra queries.
        if (!deep && prior != null && prior.lastModified == lastModified) {
            return prior.copy(libraryId = libraryId, relativePath = relPath.takeIf { it.isNotEmpty() })
        }

        val meta = readMetadata(uri)
        // Preserve an existing valid thumbnail; otherwise (or if it's gone) generate one, reusing
        // the bounds we already decoded instead of probing the file again.
        val thumb = prior?.thumbnailUri
            ?.takeIf { it.isNotBlank() && fileExistsForUri(it) }
            ?: generateThumbnail(uri, meta?.rawWidth ?: 0, meta?.rawHeight ?: 0)

        return Photo(
            id = prior?.id ?: UUID.randomUUID().toString(),
            libraryId = libraryId,
            uri = uriStr,
            displayName = name,
            width = meta?.width,
            height = meta?.height,
            dateTaken = meta?.dateTakenMs,
            lastModified = lastModified,
            sizeBytes = sizeBytes,
            mimeType = mime ?: meta?.mimeType,
            relativePath = relPath.takeIf { it.isNotEmpty() },
            thumbnailUri = thumb,
            dateAdded = prior?.dateAdded ?: System.currentTimeMillis(),
        )
    }

    private data class PhotoMeta(
        val width: Int?,
        val height: Int?,
        // Pre-rotation dimensions, kept for the thumbnail's subsample calculation.
        val rawWidth: Int,
        val rawHeight: Int,
        val dateTakenMs: Long?,
        val mimeType: String?,
    )

    // Best-effort metadata: a bounds-only decode (no pixels allocated) for resolution and EXIF for
    // capture time/orientation. Corrupt or exotic files never abort the scan — the photo is kept
    // with just its file name. Orientation is applied so width/height reflect display orientation.
    private fun readMetadata(uri: Uri): PhotoMeta? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
        val rawW = opts.outWidth.takeIf { it > 0 }
        val rawH = opts.outHeight.takeIf { it > 0 }

        var dateTaken: Long? = null
        var swap = false
        // EXIF applies to JPEG/HEIF and is best-effort everywhere else (PNG/GIF just return null).
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                swap = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90,
                    ExifInterface.ORIENTATION_ROTATE_270,
                    ExifInterface.ORIENTATION_TRANSPOSE,
                    ExifInterface.ORIENTATION_TRANSVERSE -> true
                    else -> false
                }
                dateTaken = exifDateMs(
                    exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                )
            }
        }

        PhotoMeta(
            width = if (swap) rawH else rawW,
            height = if (swap) rawW else rawH,
            rawWidth = rawW ?: 0,
            rawHeight = rawH ?: 0,
            dateTakenMs = dateTaken,
            mimeType = opts.outMimeType,
        )
    }.getOrNull()

    // EXIF datetimes are "yyyy:MM:dd HH:mm:ss" in local time; unparseable values become null.
    private fun exifDateMs(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(value)?.time
        }.getOrNull()
    }

    // Thumbnail cache lives in app-internal storage (no extra permission, never uploaded). Files
    // are named by a hash of the photo uri so re-scans reuse existing thumbs. The source is decoded
    // subsampled — the full-resolution bitmap is never loaded for a list thumbnail.
    private val thumbCacheDir: File by lazy {
        File(context.filesDir, "photo_thumbs").apply { mkdirs() }
    }

    // [knownWidth]/[knownHeight] come from the metadata pass so the file isn't probed twice; when
    // unknown (metadata failed) a bounds decode fills them in.
    private fun generateThumbnail(uri: Uri, knownWidth: Int, knownHeight: Int): String? {
        val file = File(thumbCacheDir, "${sha1(uri.toString())}.jpg")
        if (file.exists() && file.length() > 0) return Uri.fromFile(file).toString()
        return runCatching {
            var w = knownWidth
            var h = knownHeight
            if (w <= 0 || h <= 0) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                w = bounds.outWidth
                h = bounds.outHeight
            }
            if (w <= 0 || h <= 0) return@runCatching null

            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(w, h, THUMB_MAX_DIM) }
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return@runCatching null

            FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 82, out) }
            bmp.recycle()
            Uri.fromFile(file).toString().takeIf { file.length() > 0 }
        }.getOrElse { Timber.w(it, "Thumbnail generation failed for $uri"); null }
    }

    // Power-of-two subsample factor that brings the longest edge at or under [maxDim].
    private fun sampleSize(w: Int, h: Int, maxDim: Int): Int {
        var sample = 1
        var longest = maxOf(w, h)
        while (longest / 2 >= maxDim) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun fileExistsForUri(fileUri: String): Boolean =
        runCatching { Uri.parse(fileUri).path?.let { File(it).exists() } == true }.getOrDefault(false)

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
