package com.playfieldportal.feature.library.scanner

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.playfieldportal.core.data.saf.SafChild
import com.playfieldportal.core.data.saf.matchesCachedFile
import com.playfieldportal.core.data.saf.walkSafTree
import com.playfieldportal.core.data.video.VideoFileFilter
import com.playfieldportal.core.domain.model.Video
import com.playfieldportal.core.domain.model.VideoLibrary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

// Average-luminance threshold (0..255) below which a sampled frame is treated as "too dark".
private const val BRIGHT_ENOUGH = 30.0

// Longest edge of a generated thumbnail, in px. Frames are decoded STRAIGHT to this size rather
// than decoded full-size and scaled down: a 4K frame is 3840*2160*4 = 33MB, and the brightness
// search below holds one while decoding the next. Decoding to bounds is what makes concurrent
// probing affordable at all, and it shrinks the cached files too.
private const val THUMB_MAX_DIM = 640

// Concurrent per-file probes. Safe at this width only because frames are decoded to THUMB_MAX_DIM
// rather than full resolution: a worker holds well under a megabyte instead of tens of them.
private const val SCAN_PARALLELISM = 8

sealed interface VideoScanResult {
    data class Progress(val libraryName: String, val filesSeen: Int, val videosFound: Int) : VideoScanResult
    /** The tree signature matched the caller's: nothing was probed and nothing needs storing. */
    data class Unchanged(val libraryId: String) : VideoScanResult
    data class Complete(
        val libraryId: String,
        val videos: List<Video>,
        /** Store this alongside the rows; pass it back as `knownSignature` next time. */
        val signature: String,
    ) : VideoScanResult
    data class Error(val libraryId: String, val message: String) : VideoScanResult
}

/**
 * Walks a [VideoLibrary]'s SAF document tree and emits the video files it finds. Always
 * user-initiated (never background/observer-driven). Mirrors [MusicScanner]; runs on
 * [Dispatchers.IO], skips unreadable/non-video files with a log rather than crashing, and is
 * cancellable via [coroutineContext.ensureActive].
 *
 * A `knownSignature` lets the caller skip the scan entirely: when the walk's signature matches,
 * nothing under the root has been added, removed or resized since that signature was taken, and
 * the scan emits [VideoScanResult.Unchanged] without probing a single file.
 *
 * Two modes (both add new files and drop files that no longer exist — i.e. stale entries are
 * always pruned):
 *  - **Quick** ([deep] = false): for files whose mtime AND size are unchanged, the existing row is
 *    reused verbatim (metadata, thumbnail, resume position, custom fields) — no per-file
 *    MediaMetadataRetriever cost. Only new/modified files are probed.
 *  - **Deep** ([deep] = true): every file's metadata is re-read and any missing thumbnail is
 *    regenerated, while user data (custom title/thumbnail, resume position) and an existing valid
 *    thumbnail are preserved keyed by uri.
 */
@Singleton
class VideoScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun scan(
        library: VideoLibrary,
        deep: Boolean,
        existing: List<Video>,
        knownSignature: String? = null,
    ): Flow<VideoScanResult> = channelFlow {
        val treeUri = runCatching { Uri.parse(library.treeUri) }.getOrNull()
        val root = treeUri?.let { DocumentFile.fromTreeUri(context, it) }
        if (treeUri == null || root == null || !root.canRead()) {
            send(VideoScanResult.Error(library.id, "Permission lost, re-select folder."))
            return@channelFlow
        }

        val startMs = System.currentTimeMillis()
        Timber.i("Video scan started (${if (deep) "deep" else "quick"}): \"${library.displayName}\"")

        // Phase 1: enumerate, cursor-only. Cheap enough to run just to answer "did anything
        // change?" — which is exactly what the signature comparison below does.
        val walk = context.walkSafTree(treeUri, library.scanRecursively)
        if (!deep && knownSignature != null && walk.signature == knownSignature) {
            Timber.i("Video scan skipped — tree unchanged: \"${library.displayName}\" (${walk.signature})")
            send(VideoScanResult.Unchanged(library.id))
            return@channelFlow
        }

        if (!deep) {
            // Distinguishes "first scan, nothing stored yet" from "signature moved" — the
            // two reasons a card open still pays for a full pass, with different fixes.
            if (knownSignature == null) Timber.i("Video scan proceeding — no stored signature (${walk.signature})")
            else Timber.i("Video scan proceeding — signature moved: stored=$knownSignature computed=${walk.signature}")
        }

        // Phase 2: probe. Unchanged files return from the quick-scan cache without I/O; only
        // new/changed files pay for MediaMetadataRetriever and thumbnail generation.
        val byUri = existing.associateBy { it.uri }
        val timing = ProbeTiming()
        val processed = AtomicInteger(0)
        val found = AtomicInteger(0)
        val semaphore = Semaphore(SCAN_PARALLELISM)
        val videos = coroutineScope {
            walk.files.map { (child, relPath) ->
                async {
                    semaphore.withPermit {
                        val video = runCatching {
                            child.toVideoOrNull(library.id, relPath, deep, byUri, timing)
                        }.getOrElse { e ->
                            if (e is CancellationException) throw e
                            Timber.w(e, "Skipping unreadable file ${child.uri}")
                            null
                        }
                        if (video != null) found.incrementAndGet()
                        val done = processed.incrementAndGet()
                        if (done % 25 == 0) {
                            trySend(VideoScanResult.Progress(library.displayName, done, found.get()))
                        }
                        video
                    }
                }
            }.awaitAll().filterNotNull()
        }

        val took = System.currentTimeMillis() - startMs
        Timber.i(
            "Video scan complete: \"${library.displayName}\" — ${videos.size} videos from " +
                "${walk.files.size} files in ${took}ms${timing.summary()}"
        )
        send(VideoScanResult.Complete(library.id, videos, walk.signature))
    }.flowOn(Dispatchers.IO)

    private fun SafChild.toVideoOrNull(
        libraryId: String,
        relPath: String,
        deep: Boolean,
        existingByUri: Map<String, Video>,
        timing: ProbeTiming,
    ): Video? {
        if (!VideoFileFilter.isVideo(name, mime)) return null

        val uriStr = uri.toString()
        val prior = existingByUri[uriStr]

        // Quick scan: reuse an unchanged file's row wholesale.
        if (!deep && prior != null && matchesCachedFile(prior.lastModified, prior.sizeBytes)) {
            return prior.copy(libraryId = libraryId, relativePath = relPath.takeIf { it.isNotEmpty() })
        }

        timing.countProbe()
        val meta = readMetadata(uri, timing)
        // Preserve an existing valid thumbnail; otherwise (or if it's gone) generate one.
        val thumb = prior?.thumbnailUri
            ?.takeIf { it.isNotBlank() && fileExistsForUri(it) }
            ?: generateThumbnail(uri, meta?.durationMs, meta?.width, meta?.height, timing)

        return Video(
            id = prior?.id ?: UUID.randomUUID().toString(),
            libraryId = libraryId,
            uri = uriStr,
            displayName = name,
            // Preserve the user's custom title/thumbnail/resume state across re-scans.
            title = prior?.title,
            durationMs = meta?.durationMs,
            width = meta?.width,
            height = meta?.height,
            frameRate = meta?.frameRate,
            codec = meta?.codec,
            mimeType = mime ?: meta?.mimeType,
            sizeBytes = sizeBytes,
            dateAdded = prior?.dateAdded ?: System.currentTimeMillis(),
            lastModified = lastModified,
            relativePath = relPath.takeIf { it.isNotEmpty() },
            thumbnailUri = thumb,
            customThumbnailUri = prior?.customThumbnailUri,
            resumePositionMs = prior?.resumePositionMs ?: 0,
            lastWatchedAt = prior?.lastWatchedAt,
            isFavorite = prior?.isFavorite ?: false,
        )
    }

    private data class VideoMeta(
        val durationMs: Long?,
        val width: Int?,
        val height: Int?,
        val frameRate: Float?,
        val codec: String?,
        val mimeType: String?,
    )

    // Best-effort metadata. MediaMetadataRetriever throws on DRM/odd files — never let that abort
    // the scan; we still keep the video using its file name. Rotation is applied so width/height
    // reflect the displayed orientation.
    private fun readMetadata(uri: Uri, timing: ProbeTiming): VideoMeta? {
        val openedAt = System.nanoTime()
        return runCatching {
            MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(context, uri)
                val rawW = mmr.int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val rawH = mmr.int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val rotation = mmr.int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0
                val swap = rotation == 90 || rotation == 270
                val mime = mmr.str(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                VideoMeta(
                    durationMs = mmr.str(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                    width = if (swap) rawH else rawW,
                    height = if (swap) rawW else rawH,
                    frameRate = mmr.str(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull(),
                    codec = mime?.substringAfter('/', "")?.takeIf { it.isNotBlank() }?.uppercase(),
                    mimeType = mime,
                )
            }
        }.getOrNull().also { timing.addMetadata(openedAt) }
    }

    private fun MediaMetadataRetriever.str(key: Int): String? =
        runCatching { extractMetadata(key)?.takeIf { it.isNotBlank() } }.getOrNull()

    private fun MediaMetadataRetriever.int(key: Int): Int? = str(key)?.toIntOrNull()

    // Thumbnail cache lives in app-internal storage (no extra permission). Files are named by a
    // hash of the video uri so re-scans reuse existing frames. Grabs a frame ~10% in (min 1s) so
    // it isn't a black intro frame; falls back to the first sync frame.
    private val thumbCacheDir: File by lazy {
        File(context.filesDir, "video_thumbs").apply { mkdirs() }
    }

    // Timed separately from the metadata read above: this opens the file a SECOND time and
    // samples up to five frames, so if it dominates, deferring thumbnails out of the scan is the
    // lever — not parallelism.
    private fun generateThumbnail(
        uri: Uri,
        durationMs: Long?,
        frameWidth: Int?,
        frameHeight: Int?,
        timing: ProbeTiming,
    ): String? {
        val file = File(thumbCacheDir, "${sha1(uri.toString())}.jpg")
        if (file.exists() && file.length() > 0) return Uri.fromFile(file).toString()
        val startedAt = System.nanoTime()
        return runCatching {
            MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(context, uri)
                val dur = durationMs
                    ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: 0L
                // Sample a few frames across the clip and keep the first that isn't near-black (skips
                // dark intros/fades); fall back to the brightest sampled frame, then any frame.
                val candidatesUs = if (dur > 0) {
                    listOf(0.20, 0.35, 0.50, 0.65, 0.10).map { ((dur * it).toLong().coerceAtLeast(1000L)) * 1000L }
                } else {
                    listOf(1_000_000L)
                }
                val (dstW, dstH) = thumbnailBounds(frameWidth, frameHeight)
                var best: Bitmap? = null
                var bestScore = -1.0
                for (us in candidatesUs) {
                    // Scaled decode: the codec produces a thumbnail-sized frame directly, so the
                    // full-resolution bitmap never exists. Available since API 27; minSdk is 29.
                    val f = mmr.getScaledFrameAtTime(
                        us,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        dstW,
                        dstH,
                    ) ?: continue
                    val score = averageLuma(f)
                    if (score >= BRIGHT_ENOUGH) { best?.recycle(); best = f; break }
                    if (score > bestScore) { best?.recycle(); best = f; bestScore = score } else f.recycle()
                }
                // Last resort for a codec that refuses every sync-frame seek. Still scaled, so
                // no path through this function allocates a full-resolution bitmap.
                val frame = best
                    ?: mmr.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dstW, dstH)
                    ?: return@runCatching null
                FileOutputStream(file).use { out -> frame.compress(Bitmap.CompressFormat.JPEG, 85, out) }
                frame.recycle()
                Uri.fromFile(file).toString()
            }
        }.getOrElse { Timber.w(it, "Thumbnail generation failed for $uri"); null }
            .also { timing.addArtwork(startedAt) }
    }

    /**
     * The box a frame is decoded into: [THUMB_MAX_DIM] on the long edge, aspect ratio preserved,
     * never upscaled. Falls back to a square box when the metadata read gave us no dimensions —
     * the decoder still fits the frame inside it rather than stretching.
     *
     * [frameWidth]/[frameHeight] come from the metadata pass and are already rotation-corrected,
     * so a portrait clip gets a portrait box.
     */
    private fun thumbnailBounds(frameWidth: Int?, frameHeight: Int?): Pair<Int, Int> {
        val w = frameWidth ?: 0
        val h = frameHeight ?: 0
        if (w <= 0 || h <= 0) return THUMB_MAX_DIM to THUMB_MAX_DIM
        val scale = min(1.0, THUMB_MAX_DIM.toDouble() / max(w, h))
        return max(1, (w * scale).toInt()) to max(1, (h * scale).toInt())
    }

    // Rough average luminance (0..255) over a sparse grid — cheap "is this frame basically black?".
    private fun averageLuma(bmp: Bitmap): Double {
        val steps = 8
        val w = bmp.width.coerceAtLeast(1)
        val h = bmp.height.coerceAtLeast(1)
        var sum = 0.0
        var n = 0
        var yi = 0
        while (yi < steps) {
            var xi = 0
            while (xi < steps) {
                val px = bmp.getPixel((w - 1) * xi / (steps - 1), (h - 1) * yi / (steps - 1))
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                sum += 0.299 * r + 0.587 * g + 0.114 * b
                n++
                xi++
            }
            yi++
        }
        return if (n == 0) 0.0 else sum / n
    }

    private fun fileExistsForUri(fileUri: String): Boolean =
        runCatching { Uri.parse(fileUri).path?.let { File(it).exists() } == true }.getOrDefault(false)

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
