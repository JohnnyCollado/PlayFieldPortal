package com.playfieldportal.feature.library.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.playfieldportal.core.data.music.AudioFileFilter
import com.playfieldportal.core.data.saf.SafChild
import com.playfieldportal.core.data.saf.matchesCachedFile
import com.playfieldportal.core.data.saf.walkSafTree
import com.playfieldportal.core.domain.model.MusicFolder
import com.playfieldportal.core.domain.model.MusicTrack
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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

// Concurrent per-file probes. Higher than photo/video on purpose: a music worker holds only the
// embedded-art byte array (~118KB on a real library), where a photo worker holds a decoded bitmap
// and a video worker a decoded frame. Measured on a 1387-track library, probing is ~96%
// setDataSource — pure file I/O, which is what parallelism is for. At four the semaphore was
// saturated (~3.8x effective), so the cap, not the storage, was the limit.
private const val SCAN_PARALLELISM = 8

// ConcurrentHashMap forbids null values, but the album-art cache must distinguish "this album has
// no embedded art" from "not looked up yet" — otherwise every track of an art-less album re-reads
// it. This sentinel is the former.
private const val NO_ART = ""

sealed interface MusicScanResult {
    data class Progress(val folderName: String, val filesSeen: Int, val tracksFound: Int) : MusicScanResult
    /** The tree signature matched the caller's: nothing was probed and nothing needs storing. */
    data class Unchanged(val folderId: String) : MusicScanResult
    data class Complete(
        val folderId: String,
        val tracks: List<MusicTrack>,
        /** Store this alongside the rows; pass it back as `knownSignature` next time. */
        val signature: String,
    ) : MusicScanResult
    data class Error(val folderId: String, val message: String) : MusicScanResult
}

/**
 * Walks a [MusicFolder]'s SAF document tree and emits the audio tracks it finds. Always
 * user-initiated (never background/observer-driven). Skips unreadable or non-audio files with a
 * log rather than crashing, and runs on [Dispatchers.IO]. The caller persists the result via
 * MusicRepository.replaceTracksForFolder and drives progress notifications.
 *
 * A `knownSignature` lets the caller skip the scan entirely: when the walk's signature matches,
 * nothing under the root has been added, removed or resized since that signature was taken, and
 * the scan emits [MusicScanResult.Unchanged] without probing a single file.
 *
 * Two modes (both prune tracks whose files are gone):
 *  - **Missing** ([deep] = false): a file whose mtime AND size are unchanged reuses its existing
 *    row verbatim — no MediaMetadataRetriever/art cost. Only new/changed files are probed.
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
        knownSignature: String? = null,
    ): Flow<MusicScanResult> = channelFlow {
        val treeUri = runCatching { Uri.parse(folder.treeUri) }.getOrNull()
        val root = treeUri?.let { DocumentFile.fromTreeUri(context, it) }
        if (treeUri == null || root == null || !root.canRead()) {
            // SAF permission revoked or the volume is gone — surface a recoverable message.
            send(MusicScanResult.Error(folder.id, "Permission lost, re-select folder."))
            return@channelFlow
        }

        val startMs = System.currentTimeMillis()
        Timber.i("Music scan started: \"${folder.displayName}\" (${folder.treeUri})")

        // Phase 1: enumerate, cursor-only. Music folders are always recursive — `music_folders`
        // has no scan_recursively column, unlike photo and video libraries.
        val walk = context.walkSafTree(treeUri, recursive = true)
        if (!deep && knownSignature != null && walk.signature == knownSignature) {
            Timber.i("Music scan skipped — tree unchanged: \"${folder.displayName}\" (${walk.signature})")
            send(MusicScanResult.Unchanged(folder.id))
            return@channelFlow
        }

        if (!deep) {
            // Distinguishes "first scan, nothing stored yet" from "signature moved" — the
            // two reasons a card open still pays for a full pass, with different fixes.
            if (knownSignature == null) Timber.i("Music scan proceeding — no stored signature (${walk.signature})")
            else Timber.i("Music scan proceeding — signature moved: stored=$knownSignature computed=${walk.signature}")
        }

        // Phase 2: probe, with bounded parallelism. Quick-scan hits return without touching the
        // file; only new/changed files pay for a retriever. Album art is deduped within a scan —
        // the first track of an album writes the cached file, every other track of that album
        // reuses its uri — keyed by "artist|album".
        val byUri = existing.associateBy { it.uri }
        val artByAlbum = ConcurrentHashMap<String, String>()
        val timing = ProbeTiming()
        val processed = AtomicInteger(0)
        val found = AtomicInteger(0)
        val semaphore = Semaphore(SCAN_PARALLELISM)
        val tracks = coroutineScope {
            walk.files.map { (child, relPath) ->
                async {
                    semaphore.withPermit {
                        val track = runCatching {
                            child.toTrackOrNull(folder.id, relPath, artByAlbum, deep, byUri, timing)
                        }.getOrElse { e ->
                            if (e is CancellationException) throw e
                            Timber.w(e, "Skipping unreadable file ${child.uri}")
                            null
                        }
                        if (track != null) found.incrementAndGet()
                        val done = processed.incrementAndGet()
                        if (done % 25 == 0) {
                            trySend(MusicScanResult.Progress(folder.displayName, done, found.get()))
                        }
                        track
                    }
                }
            }.awaitAll().filterNotNull()
        }

        val took = System.currentTimeMillis() - startMs
        Timber.i(
            "Music scan complete: \"${folder.displayName}\" — ${tracks.size} tracks from " +
                "${walk.files.size} files in ${took}ms${timing.summary()}"
        )
        send(MusicScanResult.Complete(folder.id, tracks, walk.signature))
    }.flowOn(Dispatchers.IO)

    private fun SafChild.toTrackOrNull(
        folderId: String,
        relPath: String,
        artByAlbum: ConcurrentHashMap<String, String>,
        deep: Boolean,
        existingByUri: Map<String, MusicTrack>,
        timing: ProbeTiming,
    ): MusicTrack? {
        if (!AudioFileFilter.isAudio(name, mime)) return null

        val prior = existingByUri[uri.toString()]
        // Missing scan: reuse an unchanged file's row wholesale — no metadata or art extraction.
        if (!deep && prior != null && matchesCachedFile(prior.lastModified, prior.sizeBytes)) {
            return prior.copy(folderId = folderId, relativePath = relPath.takeIf { it.isNotEmpty() })
        }

        timing.countProbe()
        val trackId = prior?.id ?: UUID.randomUUID().toString()
        val meta = readMetadata(uri, timing)
        // Resolve album art, reusing one cached file per album so a 20-track album writes once.
        val albumKey = "${meta?.artist.orEmpty()}|${meta?.album.orEmpty()}"
            .takeIf { meta?.album?.isNotBlank() == true }
        val artUri = if (albumKey == null) {
            cacheAlbumArt(meta?.artwork, trackId, timing)
        } else {
            // Benign race: two workers can resolve the same album at once and both write. The
            // cache file is keyed by a hash of the album, and the write is atomic (temp + rename),
            // so either winner leaves a complete, identical file.
            when (val known = artByAlbum[albumKey]) {
                null -> cacheAlbumArt(meta?.artwork, albumKey, timing)
                    .also { artByAlbum[albumKey] = it ?: NO_ART }
                NO_ART -> null
                else -> known
            }
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
    private fun readMetadata(uri: Uri, timing: ProbeTiming): TrackMeta? = runCatching {
        MediaMetadataRetriever().use { mmr ->
            val openedAt = System.nanoTime()
            mmr.setDataSource(context, uri)
            val meta = TrackMeta(
                title = mmr.str(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = mmr.str(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = mmr.str(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = mmr.str(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                trackNumber = mmr.str(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')?.trim()?.toIntOrNull(),
                mimeType = mmr.str(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                artwork = null,
            )
            timing.addMetadata(openedAt)

            // Timed separately from the tags above: embedded art is often 100KB-4MB per file, and
            // whether it is worth extracting for every track (rather than once per album) depends
            // entirely on how this compares to the open cost.
            val artAt = System.nanoTime()
            val artwork = runCatching { mmr.embeddedPicture }.getOrNull()
            timing.addArtwork(artAt)
            meta.copy(artwork = artwork)
        }
    }.getOrNull()

    private fun MediaMetadataRetriever.str(key: Int): String? =
        runCatching { extractMetadata(key)?.takeIf { it.isNotBlank() } }.getOrNull()

    // Album art cache lives in app-internal storage so it needs no extra permission. Files are
    // named by a hash of their dedup key (album or track id), so re-scans reuse existing files.
    private val artCacheDir: File by lazy {
        File(context.filesDir, "music_art").apply { mkdirs() }
    }

    private fun cacheAlbumArt(bytes: ByteArray?, key: String, timing: ProbeTiming): String? {
        if (bytes == null || bytes.isEmpty()) return null
        val startedAt = System.nanoTime()
        return runCatching {
            val file = File(artCacheDir, "${sha1(key)}.img")
            // Written via temp + rename so two workers resolving the same album concurrently can
            // never leave a half-written file behind for the UI to decode.
            if (!file.exists()) {
                val tmp = File(artCacheDir, "${file.name}.${UUID.randomUUID()}.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) tmp.delete()
            }
            Uri.fromFile(file).toString()
        }.getOrNull().also { timing.addArtCache(startedAt) }
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
