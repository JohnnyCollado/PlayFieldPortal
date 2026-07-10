package com.playfieldportal.feature.library.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.playfieldportal.core.data.music.AudioFileFilter
import com.playfieldportal.core.data.saf.SafChild
import com.playfieldportal.core.data.saf.hasNoMediaMarker
import com.playfieldportal.core.data.saf.isIgnoredDir
import com.playfieldportal.core.data.saf.querySafChildren
import com.playfieldportal.core.data.saf.safScanStartDocId
import com.playfieldportal.core.domain.model.MusicFolder
import com.playfieldportal.core.domain.model.MusicTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
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
 *
 * Two modes (both prune tracks whose files are gone):
 *  - **Missing** ([deep] = false): a file whose `lastModified` is unchanged reuses its existing row
 *    verbatim — no MediaMetadataRetriever/art cost. Only new/changed files are probed.
 *  - **Deep** ([deep] = true): every file's metadata and album art is re-read.
 */
@Singleton
class MusicScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun scan(
        folder: MusicFolder,
        deep: Boolean = true,
        existing: List<MusicTrack> = emptyList(),
    ): Flow<MusicScanResult> = flow {
        val treeUri = runCatching { Uri.parse(folder.treeUri) }.getOrNull()
        val root = treeUri?.let { DocumentFile.fromTreeUri(context, it) }
        if (treeUri == null || root == null || !root.canRead()) {
            // SAF permission revoked or the volume is gone — surface a recoverable message.
            emit(MusicScanResult.Error(folder.id, "Permission lost, re-select folder."))
            return@flow
        }

        val startMs = System.currentTimeMillis()
        Timber.i("Music scan started: \"${folder.displayName}\" (${folder.treeUri})")
        val tracks = mutableListOf<MusicTrack>()
        val byUri = existing.associateBy { it.uri }
        // Album art is deduped within a scan: the first track of an album writes the cached file,
        // every other track of that album reuses the same uri. Keyed by "artist|album".
        val artByAlbum = HashMap<String, String?>()
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
                    if (child.isIgnoredDir()) continue
                    stack.addLast(child.documentId to if (relPath.isEmpty()) child.name else "$relPath/${child.name}")
                    continue
                }
                filesSeen++
                val track = runCatching { child.toTrackOrNull(folder.id, relPath, artByAlbum, deep, byUri) }
                    .getOrElse { Timber.w(it, "Skipping unreadable file ${child.uri}"); null }
                if (track != null) tracks.add(track)

                if (filesSeen % 25 == 0) {
                    emit(MusicScanResult.Progress(folder.displayName, filesSeen, tracks.size))
                }
            }
        }

        val took = System.currentTimeMillis() - startMs
        Timber.i("Music scan complete: \"${folder.displayName}\" — ${tracks.size} tracks from $filesSeen files in ${took}ms")
        emit(MusicScanResult.Complete(folder.id, tracks))
    }.flowOn(Dispatchers.IO)

    private fun SafChild.toTrackOrNull(
        folderId: String,
        relPath: String,
        artByAlbum: MutableMap<String, String?>,
        deep: Boolean,
        existingByUri: Map<String, MusicTrack>,
    ): MusicTrack? {
        if (!AudioFileFilter.isAudio(name, mime)) return null

        val prior = existingByUri[uri.toString()]
        // Missing scan: reuse an unchanged file's row wholesale — no metadata or art extraction.
        if (!deep && prior != null && prior.lastModified == lastModified) {
            return prior.copy(folderId = folderId, relativePath = relPath.takeIf { it.isNotEmpty() })
        }

        val trackId = prior?.id ?: UUID.randomUUID().toString()
        val meta = readMetadata(uri)
        // Resolve album art, reusing one cached file per album so a 20-track album writes once.
        val albumKey = "${meta?.artist.orEmpty()}|${meta?.album.orEmpty()}"
            .takeIf { meta?.album?.isNotBlank() == true }
        val artUri = if (albumKey != null && artByAlbum.containsKey(albumKey)) {
            artByAlbum[albumKey]
        } else {
            val cached = cacheAlbumArt(meta?.artwork, albumKey ?: trackId)
            if (albumKey != null) artByAlbum[albumKey] = cached
            cached
        }

        return MusicTrack(
            id = trackId,
            folderId = folderId,
            uri = uri.toString(),
            displayName = name,
            title = meta?.title,
            artist = meta?.artist,
            album = meta?.album,
            durationMs = meta?.durationMs,
            mimeType = mime ?: meta?.mimeType,
            sizeBytes = sizeBytes,
            lastModified = lastModified,
            trackNumber = meta?.trackNumber,
            relativePath = relPath.takeIf { it.isNotEmpty() },
            artUri = artUri,
        )
    }

    private data class TrackMeta(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long?,
        val trackNumber: Int?,
        val mimeType: String?,
        val artwork: ByteArray?,
    )

    // Best-effort metadata. MediaMetadataRetriever throws on DRM/odd files — never let that abort
    // the scan; we still keep the track using its file name. Embedded art (if any) comes from the
    // same retriever so we never open the file twice.
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
                artwork = runCatching { mmr.embeddedPicture }.getOrNull(),
            )
        }
    }.getOrNull()

    private fun MediaMetadataRetriever.str(key: Int): String? =
        runCatching { extractMetadata(key)?.takeIf { it.isNotBlank() } }.getOrNull()

    // Album art cache lives in app-internal storage so it needs no extra permission. Files are
    // named by a hash of their dedup key (album or track id), so re-scans reuse existing files.
    private val artCacheDir: File by lazy {
        File(context.filesDir, "music_art").apply { mkdirs() }
    }

    private fun cacheAlbumArt(bytes: ByteArray?, key: String): String? {
        if (bytes == null || bytes.isEmpty()) return null
        return runCatching {
            val file = File(artCacheDir, "${sha1(key)}.img")
            if (!file.exists()) file.writeBytes(bytes)
            Uri.fromFile(file).toString()
        }.getOrNull()
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
