package com.playfieldportal.feature.library.scanner

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.playfieldportal.core.data.video.VideoFileFilter
import com.playfieldportal.core.domain.model.Video
import com.playfieldportal.core.domain.model.VideoLibrary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

// Average-luminance threshold (0..255) below which a sampled frame is treated as "too dark".
private const val BRIGHT_ENOUGH = 30.0

sealed interface VideoScanResult {
    data class Progress(val libraryName: String, val filesSeen: Int, val videosFound: Int) : VideoScanResult
    data class Complete(val libraryId: String, val videos: List<Video>) : VideoScanResult
    data class Error(val libraryId: String, val message: String) : VideoScanResult
}

/**
 * Walks a [VideoLibrary]'s SAF document tree and emits the video files it finds. Always
 * user-initiated (never background/observer-driven). Mirrors [MusicScanner]; runs on
 * [Dispatchers.IO], skips unreadable/non-video files with a log rather than crashing, and is
 * cancellable via [coroutineContext.ensureActive].
 *
 * Two modes (both add new files and drop files that no longer exist — i.e. stale entries are
 * always pruned):
 *  - **Quick** ([deep] = false): for files whose `lastModified` is unchanged, the existing row is
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
    ): Flow<VideoScanResult> = flow {
        val treeUri = runCatching { Uri.parse(library.treeUri) }.getOrNull()
        val root = treeUri?.let { DocumentFile.fromTreeUri(context, it) }
        if (treeUri == null || root == null || !root.canRead()) {
            emit(VideoScanResult.Error(library.id, "Permission lost, re-select folder."))
            return@flow
        }

        val startMs = System.currentTimeMillis()
        Timber.i("Video scan started (${if (deep) "deep" else "quick"}): \"${library.displayName}\"")
        val byUri = existing.associateBy { it.uri }
        val videos = mutableListOf<Video>()
        var filesSeen = 0

        // Iterative DFS over document IDs so deeply nested trees don't blow the stack. Directory
        // listing goes through one DocumentsContract child query per directory (see SafChildren)
        // instead of DocumentFile's per-property IPC round-trips.
        val stack = ArrayDeque<Pair<String, String>>()   // documentId to relative path
        stack.addLast(safScanStartDocId(context, treeUri) to "")
        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (dirDocId, relPath) = stack.removeLast()
            val children = context.contentResolver.querySafChildren(treeUri, dirDocId)
            if (children.hasNoMediaMarker()) continue
            for (child in children) {
                coroutineContext.ensureActive()
                if (child.isDirectory) {
                    if (!library.scanRecursively) continue
                    if (child.isIgnoredDir()) continue
                    stack.addLast(child.documentId to if (relPath.isEmpty()) child.name else "$relPath/${child.name}")
                    continue
                }
                filesSeen++
                val video = runCatching { child.toVideoOrNull(library.id, relPath, deep, byUri) }
                    .getOrElse { Timber.w(it, "Skipping unreadable file ${child.uri}"); null }
                if (video != null) videos.add(video)

                if (filesSeen % 25 == 0) {
                    emit(VideoScanResult.Progress(library.displayName, filesSeen, videos.size))
                }
            }
        }

        val took = System.currentTimeMillis() - startMs
        Timber.i("Video scan complete: \"${library.displayName}\" — ${videos.size} videos from $filesSeen files in ${took}ms")
        emit(VideoScanResult.Complete(library.id, videos))
    }.flowOn(Dispatchers.IO)

    private fun SafChild.toVideoOrNull(
        libraryId: String,
        relPath: String,
        deep: Boolean,
        existingByUri: Map<String, Video>,
    ): Video? {
        if (!VideoFileFilter.isVideo(name, mime)) return null

        val uriStr = uri.toString()
        val prior = existingByUri[uriStr]

        // Quick scan: reuse an unchanged file's row wholesale.
        if (!deep && prior != null && prior.lastModified == lastModified) {
            return prior.copy(libraryId = libraryId, relativePath = relPath.takeIf { it.isNotEmpty() })
        }

        val meta = readMetadata(uri)
        // Preserve an existing valid thumbnail; otherwise (or if it's gone) generate one.
        val thumb = prior?.thumbnailUri
            ?.takeIf { it.isNotBlank() && fileExistsForUri(it) }
            ?: generateThumbnail(uri, meta?.durationMs)

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
    private fun readMetadata(uri: Uri): VideoMeta? = runCatching {
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
    }.getOrNull()

    private fun MediaMetadataRetriever.str(key: Int): String? =
        runCatching { extractMetadata(key)?.takeIf { it.isNotBlank() } }.getOrNull()

    private fun MediaMetadataRetriever.int(key: Int): Int? = str(key)?.toIntOrNull()

    // Thumbnail cache lives in app-internal storage (no extra permission). Files are named by a
    // hash of the video uri so re-scans reuse existing frames. Grabs a frame ~10% in (min 1s) so
    // it isn't a black intro frame; falls back to the first sync frame.
    private val thumbCacheDir: File by lazy {
        File(context.filesDir, "video_thumbs").apply { mkdirs() }
    }

    private fun generateThumbnail(uri: Uri, durationMs: Long?): String? {
        val file = File(thumbCacheDir, "${sha1(uri.toString())}.jpg")
        if (file.exists() && file.length() > 0) return Uri.fromFile(file).toString()
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
                var best: Bitmap? = null
                var bestScore = -1.0
                for (us in candidatesUs) {
                    val f = mmr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                    val score = averageLuma(f)
                    if (score >= BRIGHT_ENOUGH) { best?.recycle(); best = f; break }
                    if (score > bestScore) { best?.recycle(); best = f; bestScore = score } else f.recycle()
                }
                val frame = best ?: mmr.frameAtTime ?: return@runCatching null
                FileOutputStream(file).use { out -> frame.compress(Bitmap.CompressFormat.JPEG, 85, out) }
                frame.recycle()
                Uri.fromFile(file).toString()
            }
        }.getOrElse { Timber.w(it, "Thumbnail generation failed for $uri"); null }
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
