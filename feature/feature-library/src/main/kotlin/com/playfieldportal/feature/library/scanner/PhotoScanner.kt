package com.playfieldportal.feature.library.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.util.Size
import androidx.documentfile.provider.DocumentFile
import com.playfieldportal.core.data.photo.PhotoFileFilter
import com.playfieldportal.core.data.saf.SafChild
import com.playfieldportal.core.data.saf.matchesCachedFile
import com.playfieldportal.core.data.saf.walkSafTree
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

// A provider thumbnail shorter than this fraction of THUMB_MAX_DIM on its long edge is rejected
// in favour of a real decode. Providers may answer a 320px request with the image's embedded EXIF
// thumbnail, which is commonly 160x120 — fast, but visibly soft on the flyout. This is the
// speed/quality dial: lower it to take more provider thumbnails, raise it to decode more.
private const val MIN_PROVIDER_THUMB_RATIO = 0.75

// Longest edge of a generated list thumbnail, in px. Small enough to decode fast and cache cheap,
// large enough for the 60×40 list tile and the flyout.
private const val THUMB_MAX_DIM = 320

// Concurrent per-file probes (decode bounds + EXIF + thumbnail). Bounded so a folder full of huge
// images can't exhaust memory or starve the device. The bounds pass allocates no pixels
// (inJustDecodeBounds) and the thumbnail decode is subsampled to at most ~2x THUMB_MAX_DIM, so a
// worker holds ~1.6MB at worst — eight in flight is low tens of MB even on a folder of 100MP
// images, and the probe is otherwise file I/O.
private const val SCAN_PARALLELISM = 8

sealed interface PhotoScanResult {
    data class Progress(val libraryName: String, val filesSeen: Int, val photosFound: Int) : PhotoScanResult
    /** The tree signature matched the caller's: nothing was probed and nothing needs storing. */
    data class Unchanged(val libraryId: String) : PhotoScanResult
    data class Complete(
        val libraryId: String,
        val photos: List<Photo>,
        /** Store this alongside the rows; pass it back as `knownSignature` next time. */
        val signature: String,
    ) : PhotoScanResult
    data class Error(val libraryId: String, val message: String) : PhotoScanResult
}

/**
 * Walks a [PhotoLibrary]'s SAF document tree and emits the image files it finds. Always
 * user-initiated (never background/observer-driven). Runs on [Dispatchers.IO], skips
 * unreadable/corrupt files with a log rather than crashing, and is cancellable via
 * [coroutineContext.ensureActive].
 *
 * Directory listing goes through the shared cursor-only walker (`Context.walkSafTree`) — one
 * child query per directory returns name/MIME/mtime/size for every entry in a single cursor.
 * (DocumentFile would issue a separate ContentResolver query per property per file, which made
 * scans of photo folders — many small files — take ~6 IPC round-trips each before any image work.)
 *
 * A `knownSignature` lets the caller skip the scan entirely: when the walk's signature matches,
 * nothing under the root has been added, removed or resized since that signature was taken, and
 * the scan emits [PhotoScanResult.Unchanged] without probing a single file.
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
        knownSignature: String? = null,
    ): Flow<PhotoScanResult> = channelFlow {
        val treeUri = runCatching { Uri.parse(library.treeUri) }.getOrNull()
        val root = treeUri?.let { DocumentFile.fromTreeUri(context, it) }
        if (treeUri == null || root == null || !root.canRead()) {
            send(PhotoScanResult.Error(library.id, "Permission lost, re-select folder."))
            return@channelFlow
        }

        val startMs = System.currentTimeMillis()
        Timber.i("Photo scan started (${if (deep) "deep" else "quick"}): \"${library.displayName}\"")
        // ── Phase 1: enumerate candidate files — cursor-only, one query per directory. ─────
        val walk = context.walkSafTree(treeUri, library.scanRecursively)
        if (!deep && knownSignature != null && walk.signature == knownSignature) {
            Timber.i("Photo scan skipped — tree unchanged: \"${library.displayName}\" (${walk.signature})")
            send(PhotoScanResult.Unchanged(library.id))
            return@channelFlow
        }

        if (!deep) {
            // Distinguishes "first scan, nothing stored yet" from "signature moved" — the
            // two reasons a card open still pays for a full pass, with different fixes.
            if (knownSignature == null) Timber.i("Photo scan proceeding — no stored signature (${walk.signature})")
            else Timber.i("Photo scan proceeding — signature moved: stored=$knownSignature computed=${walk.signature}")
        }

        val byUri = existing.associateBy { it.uri }
        val files = walk.files
        send(PhotoScanResult.Progress(library.displayName, files.size, 0))

        // ── Phase 2: probe files with bounded parallelism. ─────────────────────────────────
        // Quick-scan hits on unchanged files return without any I/O; new/changed files decode
        // bounds + EXIF + thumbnail concurrently, capped at SCAN_PARALLELISM in-flight.
        val timing = ProbeTiming()
        val processed = AtomicInteger(0)
        val found = AtomicInteger(0)
        val semaphore = Semaphore(SCAN_PARALLELISM)
        val photos = coroutineScope {
            files.map { (child, relPath) ->
                async {
                    semaphore.withPermit {
                        val photo = runCatching { child.toPhotoOrNull(library.id, relPath, deep, byUri, timing) }
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
        Timber.i(
            "Photo scan complete: \"${library.displayName}\" — ${photos.size} photos from " +
                "${files.size} files in ${took}ms${timing.summary()}"
        )
        send(PhotoScanResult.Complete(library.id, photos, walk.signature))
    }.flowOn(Dispatchers.IO)

    private fun SafChild.toPhotoOrNull(
        libraryId: String,
        relPath: String,
        deep: Boolean,
        existingByUri: Map<String, Photo>,
        timing: ProbeTiming,
    ): Photo? {
        if (!PhotoFileFilter.isPhoto(name, mime)) return null

        val uriStr = uri.toString()
        val prior = existingByUri[uriStr]

        // Quick scan: reuse an unchanged file's row wholesale — no decode, no extra queries — but
        // only while its thumbnail file is still present. If the thumbnail is gone (e.g. after
        // Clear Thumbnail Cache), fall through so a Rescan actually regenerates it.
        val priorThumb = prior?.thumbnailUri
        if (!deep && prior != null && matchesCachedFile(prior.lastModified, prior.sizeBytes) &&
            !priorThumb.isNullOrBlank() && fileExistsForUri(priorThumb)
        ) {
            return prior.copy(libraryId = libraryId, relativePath = relPath.takeIf { it.isNotEmpty() })
        }

        timing.countProbe()
        val meta = readMetadata(uri, mime, timing)
        // Preserve an existing valid thumbnail; otherwise (or if it's gone) generate one, reusing
        // the bounds we already decoded instead of probing the file again.
        val thumb = prior?.thumbnailUri
            ?.takeIf { it.isNotBlank() && fileExistsForUri(it) }
            ?: generateThumbnail(uri, meta?.rawWidth ?: 0, meta?.rawHeight ?: 0, timing)

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
    private fun readMetadata(uri: Uri, knownMime: String?, timing: ProbeTiming): PhotoMeta? {
        val startedAt = System.nanoTime()
        return readMetadataInner(uri, knownMime).also { timing.addMetadata(startedAt) }
    }

    /**
     * Orientation, capture time and dimensions — from ONE file open where the image allows it.
     *
     * Every open here is a SAF round-trip, and they are the cost: measured on a 4166-photo
     * library, this step was 52s of worker time against 48s for thumbnail generation. It used to
     * open each file twice, once for [BitmapFactory] bounds and once for EXIF, so for a camera
     * photo half that work was redundant — EXIF already carries the dimensions.
     *
     * Only `PixelXDimension`/`PixelYDimension` are trusted. They live in the EXIF SubIFD and
     * describe the MAIN image; IFD0's `ImageWidth`/`ImageLength` are the classic trap, since some
     * encoders use them for the embedded thumbnail and a photo would then be recorded as 160x120.
     */
    private fun readMetadataInner(uri: Uri, knownMime: String?): PhotoMeta? = runCatching {
        var rawW: Int? = null
        var rawH: Int? = null
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
                rawW = exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0).takeIf { it > 0 }
                rawH = exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0).takeIf { it > 0 }
            }
        }

        // Second open only when EXIF could not answer: a PNG/GIF, a JPEG with its EXIF stripped,
        // or a provider that gave us no MIME type to fall back on.
        var mimeType = knownMime
        if (rawW == null || rawH == null || mimeType == null) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            if (rawW == null || rawH == null) {
                rawW = opts.outWidth.takeIf { it > 0 }
                rawH = opts.outHeight.takeIf { it > 0 }
            }
            mimeType = mimeType ?: opts.outMimeType
        }

        PhotoMeta(
            width = if (swap) rawH else rawW,
            height = if (swap) rawW else rawH,
            rawWidth = rawW ?: 0,
            rawHeight = rawH ?: 0,
            dateTakenMs = dateTaken,
            mimeType = mimeType,
        )
    }.getOrNull()

    // EXIF datetimes are "yyyy:MM:dd HH:mm:ss" in local time; unparseable values become null.
    private fun exifDateMs(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(value)?.time
        }.getOrNull()
    }

    // Thumbnail cache lives in internal app cache (private to the app, never indexed by MediaStore,
    // evictable by the OS under storage pressure). Files are named by a hash of the photo uri so
    // re-scans reuse existing thumbs; missing ones are regenerated on the next scan. The source is
    // decoded subsampled — the full-res bitmap is never loaded here.
    val thumbnailCacheDir: File by lazy {
        deleteLegacyExternalCache()
        File(context.cacheDir, "thumbnails").apply { mkdirs() }
    }

    // Pre-migration builds kept thumbnails under app external-files (Android/data/<pkg>/files/
    // cache/thumbnails), which other apps could read on Android 10. Remove any leftovers once;
    // stale thumbnailUri rows fail the fileExistsForUri check and regenerate on the next scan.
    private fun deleteLegacyExternalCache() {
        runCatching {
            listOfNotNull(context.getExternalFilesDir(null), context.filesDir).forEach { base ->
                File(base, "cache/thumbnails").deleteRecursively()
                File(base, "cache").delete()   // only succeeds if now empty
            }
        }
    }

    /** Deletes every generated thumbnail. */
    fun clearThumbnailCache(): Int {
        val dir = thumbnailCacheDir
        return runCatching {
            dir.listFiles()?.count { it.delete() } ?: 0
        }.getOrDefault(0)
    }

    // [knownWidth]/[knownHeight] come from the metadata pass so the file isn't probed twice; when
    // unknown (metadata failed) a bounds decode fills them in.
    // Timed separately from the bounds/EXIF read above: this is the only step that decodes real
    // pixels, so if it dominates a cold scan, deferring thumbnails out of the scan is the lever.
    private fun generateThumbnail(
        uri: Uri,
        knownWidth: Int,
        knownHeight: Int,
        timing: ProbeTiming,
    ): String? {
        val file = File(thumbnailCacheDir, "${sha1(uri.toString())}.jpg")
        if (file.exists() && file.length() > 0) return Uri.fromFile(file).toString()
        val startedAt = System.nanoTime()
        return runCatching {
            // Ask the provider first, decode only if it declines. Measured on a 4166-photo
            // library, decoding dominated a cold scan: 176s of the 235s of worker time, ~42ms per
            // file, against 14ms to read bounds and EXIF. A provider that can answer from the
            // file's embedded thumbnail skips the full-image decode entirely.
            val bmp = providerThumbnail(uri)
                ?: decodedThumbnail(uri, knownWidth, knownHeight)
                ?: return@runCatching null

            FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 82, out) }
            bmp.recycle()
            Uri.fromFile(file).toString().takeIf { file.length() > 0 }
        }.getOrElse { Timber.w(it, "Thumbnail generation failed for $uri"); null }
            .also { timing.addArtwork(startedAt) }
    }

    /**
     * The DocumentsProvider's own thumbnail, when it has one worth having.
     *
     * `loadThumbnail` (API 29; minSdk is 29) lets the provider serve a thumbnail however it likes
     * — for a camera photo that is usually the JPEG's embedded EXIF thumbnail, i.e. a few
     * kilobytes already sitting in the header rather than a full multi-megapixel decode.
     *
     * It is a request, not a contract: a provider may return something much smaller than asked
     * for, or refuse outright (no FLAG_SUPPORTS_THUMBNAIL, an unreadable file). Both are normal,
     * so a null here simply means "decode it properly".
     */
    private fun providerThumbnail(uri: Uri): Bitmap? {
        val bmp = runCatching {
            context.contentResolver.loadThumbnail(uri, Size(THUMB_MAX_DIM, THUMB_MAX_DIM), null)
        }.getOrNull() ?: return null

        val longestEdge = maxOf(bmp.width, bmp.height)
        if (longestEdge >= THUMB_MAX_DIM * MIN_PROVIDER_THUMB_RATIO) return bmp
        // Too small to display well — pay for the decode instead.
        bmp.recycle()
        return null
    }

    /** Subsampled decode: correct for any readable image, and the reason it is the fallback. */
    private fun decodedThumbnail(uri: Uri, knownWidth: Int, knownHeight: Int): Bitmap? {
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
        if (w <= 0 || h <= 0) return null

        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(w, h, THUMB_MAX_DIM) }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
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
