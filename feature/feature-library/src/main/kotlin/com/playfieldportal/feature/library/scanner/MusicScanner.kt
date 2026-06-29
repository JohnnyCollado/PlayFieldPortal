package com.playfieldportal.feature.library.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.playfieldportal.core.data.music.AudioFileFilter
import com.playfieldportal.core.domain.model.MusicFolder
import com.playfieldportal.core.domain.model.MusicTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

sealed interface MusicScanResult {
    data class Progress(val folderName: String, val filesSeen: Int, val tracksFound: Int) : MusicScanResult
    data class Complete(val folderId: String, val tracks: List<MusicTrack>) : MusicScanResult
    data class Error(val folderId: String, val message: String) : MusicScanResult
}

/**
 * Walks a [MusicFolder]'s SAF document tree and emits the audio tracks it finds. Always
 * user-initiated (never background/observer-driven). Skips unreadable or non-audio files with a
 * log rather than crashing, and runs on [Dispatchers.IO]. The caller persists the result via
 * MusicRepository.replaceTracksForFolder and drives progress notifications.
 */
@Singleton
class MusicScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun scan(folder: MusicFolder): Flow<MusicScanResult> = flow {
        val treeUri = runCatching { Uri.parse(folder.treeUri) }.getOrNull()
        val root = treeUri?.let { DocumentFile.fromTreeUri(context, it) }
        if (root == null || !root.canRead()) {
            // SAF permission revoked or the volume is gone — surface a recoverable message.
            emit(MusicScanResult.Error(folder.id, "Permission lost, re-select folder."))
            return@flow
        }

        Timber.i("Music scan started: \"${folder.displayName}\" (${folder.treeUri})")
        val tracks = mutableListOf<MusicTrack>()
        var filesSeen = 0

        // Iterative DFS so deeply nested trees don't blow the stack.
        val stack = ArrayDeque<Pair<DocumentFile, String>>()
        stack.addLast(root to "")
        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (dir, relPath) = stack.removeLast()
            val children = runCatching { dir.listFiles() }.getOrElse {
                Timber.w(it, "Could not list ${dir.uri}")
                emptyArray()
            }
            for (child in children) {
                coroutineContext.ensureActive()
                if (child.isDirectory) {
                    val name = child.name ?: continue
                    stack.addLast(child to if (relPath.isEmpty()) name else "$relPath/$name")
                    continue
                }
                filesSeen++
                val track = runCatching { child.toTrackOrNull(folder.id, relPath) }
                    .getOrElse { Timber.w(it, "Skipping unreadable file ${child.uri}"); null }
                if (track != null) tracks.add(track)

                if (filesSeen % 25 == 0) {
                    emit(MusicScanResult.Progress(folder.displayName, filesSeen, tracks.size))
                }
            }
        }

        Timber.i("Music scan complete: \"${folder.displayName}\" — ${tracks.size} tracks from $filesSeen files")
        emit(MusicScanResult.Complete(folder.id, tracks))
    }.flowOn(Dispatchers.IO)

    private fun DocumentFile.toTrackOrNull(folderId: String, relPath: String): MusicTrack? {
        val name = name ?: return null
        val mime = type
        if (!AudioFileFilter.isAudio(name, mime)) return null

        val meta = readMetadata(uri)
        return MusicTrack(
            id = UUID.randomUUID().toString(),
            folderId = folderId,
            uri = uri.toString(),
            displayName = name,
            title = meta?.title,
            artist = meta?.artist,
            album = meta?.album,
            durationMs = meta?.durationMs,
            mimeType = mime ?: meta?.mimeType,
            sizeBytes = length().takeIf { it > 0 },
            lastModified = lastModified().takeIf { it > 0 },
            trackNumber = meta?.trackNumber,
            relativePath = relPath.takeIf { it.isNotEmpty() },
        )
    }

    private data class TrackMeta(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long?,
        val trackNumber: Int?,
        val mimeType: String?,
    )

    // Best-effort metadata. MediaMetadataRetriever throws on DRM/odd files — never let that abort
    // the scan; we still keep the track using its file name.
    private fun readMetadata(uri: Uri): TrackMeta? = runCatching {
        MediaMetadataRetriever().use { mmr ->
            mmr.setDataSource(context, uri)
            TrackMeta(
                title = mmr.str(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = mmr.str(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = mmr.str(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = mmr.str(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                trackNumber = mmr.str(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')?.trim()?.toIntOrNull(),
                mimeType = mmr.str(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
            )
        }
    }.getOrNull()

    private fun MediaMetadataRetriever.str(key: Int): String? =
        runCatching { extractMetadata(key)?.takeIf { it.isNotBlank() } }.getOrNull()
}
