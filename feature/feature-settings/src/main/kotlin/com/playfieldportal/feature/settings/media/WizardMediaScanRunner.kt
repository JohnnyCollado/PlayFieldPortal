package com.playfieldportal.feature.settings.media

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.playfieldportal.core.data.repository.MediaRootKind
import com.playfieldportal.core.data.repository.MediaRootRepository
import com.playfieldportal.core.domain.model.MusicFolder
import com.playfieldportal.core.domain.model.PhotoLibrary
import com.playfieldportal.core.domain.model.VideoLibrary
import com.playfieldportal.core.domain.repository.MusicRepository
import com.playfieldportal.core.domain.repository.PhotoRepository
import com.playfieldportal.core.domain.repository.VideoRepository
import com.playfieldportal.core.ui.notification.BackgroundTaskNotifier
import com.playfieldportal.feature.library.scanner.MusicScanResult
import com.playfieldportal.feature.library.scanner.MusicScanner
import com.playfieldportal.feature.library.scanner.PhotoScanResult
import com.playfieldportal.feature.library.scanner.PhotoScanner
import com.playfieldportal.feature.library.scanner.RescanApplicationScope
import com.playfieldportal.feature.library.scanner.VideoScanResult
import com.playfieldportal.feature.library.scanner.VideoScanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place a media (re)scan runs.
 *
 * Every trigger funnels through here — the setup wizard and Settings root edits, the throttled
 * resume/mount pass, the XMB card context menus, and the freshness check when a card is opened.
 * Each section RECONCILES its library rows with the configured roots (creating a row per root,
 * removing rows whose root was removed) and then scans every configured root incrementally.
 * Roots may be MULTIPLE — a music library can span internal storage plus an SD card, exactly
 * like ROM roots.
 *
 * Scans report through the shared background-task notifications and run on the shared
 * application-scoped rescan supervisor, so one survives the wizard (and its ViewModel) closing.
 * One scan per kind at a time, but the three kinds run CONCURRENTLY.
 *
 * **[force] is the distinction that matters.** A user who asked for a scan gets a real reconcile.
 * Everything automatic passes the library's stored tree signature instead, so an untouched folder
 * costs one cursor query per directory and no file probing at all.
 */
@Singleton
class WizardMediaScanRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaRootRepository: MediaRootRepository,
    private val musicRepository: MusicRepository,
    private val musicScanner: MusicScanner,
    private val photoRepository: PhotoRepository,
    private val photoScanner: PhotoScanner,
    private val videoRepository: VideoRepository,
    private val videoScanner: VideoScanner,
    @RescanApplicationScope private val scope: CoroutineScope,
) {
    private val notifier = BackgroundTaskNotifier(context)

    /**
     * The notifier, but only for a scan the USER asked for.
     *
     * Automatic passes — the resume/mount sweep and the freshness check when a card is opened —
     * report nothing. Opening the Video card should not post "Scanning Movies" followed a beat
     * later by "Up to date"; with the signature gate most automatic passes find nothing to do at
     * all, so announcing them is pure noise on a screen the user is trying to browse.
     *
     * Failures are deliberately NOT routed through this — see the Error branches.
     */
    private fun progressFor(force: Boolean): BackgroundTaskNotifier? = notifier.takeIf { force }
    private val inFlight = ConcurrentHashMap<MediaRootKind, Job>()

    // One mutex PER KIND, not one shared lock. MUSIC, PHOTO and VIDEO write to disjoint
    // repositories, so a single lock only serialized work that never conflicted: a resume pass
    // cost the SUM of all three kinds, and a user-initiated scan of one kind queued behind the
    // other two while its in-flight entry made it look already started.
    private val scanMutexes = MediaRootKind.entries.associateWith { Mutex() }

    /**
     * Starts (or restarts after completion) the scan for [kind]'s current roots. Defaults to a
     * forced scan because its caller is the wizard reacting to a root the user just changed.
     */
    fun kickoff(kind: MediaRootKind, force: Boolean = true) {
        // compute() is atomic per key, so two callers racing here cannot both launch a scan —
        // the plain get-then-put this replaced could.
        inFlight.compute(kind) { _, running ->
            if (running?.isActive == true) running else launchScan(kind, force)
        }
    }

    /**
     * Runs one pass over all media roots, without exposing per-kind trigger buckets. The three
     * kinds are started together and proceed independently: the pass costs the slowest kind
     * rather than the sum of all three, and a kind whose scan is already running is left alone.
     *
     * Signature-gated, because nothing here is a user asking for a scan — it is the throttled
     * resume/mount pass. A section whose folders are untouched costs one cursor query per
     * directory and no file probing, which is the difference between a resume that stutters and
     * one that does not.
     */
    fun kickoffAll() {
        MediaRootKind.entries.forEach { kickoff(it, force = false) }
    }

    /**
     * Scans one library/folder by id — the entry point for the XMB card context menus and for
     * the freshness check that runs when a card is opened.
     */
    fun kickoffLibrary(kind: MediaRootKind, libraryId: String, force: Boolean) {
        scope.launch {
            runCatching {
                scanMutexes.getValue(kind).withLock {
                    when (kind) {
                        MediaRootKind.MUSIC ->
                            musicRepository.getFolder(libraryId)?.let { scanMusicFolder(it, force) }
                        MediaRootKind.PHOTO ->
                            photoRepository.getLibrary(libraryId)?.let { scanPhotoLibrary(it, force) }
                        MediaRootKind.VIDEO ->
                            videoRepository.getLibrary(libraryId)?.let { scanVideoLibrary(it, force) }
                    }
                }
            }.onFailure { Timber.w(it, "Media %s library scan failed", kind.name) }
        }
    }

    /**
     * Freshness check for a whole section, for when its top-level card is opened. Signature-gated,
     * so a section whose folders are untouched does no file I/O whatsoever.
     */
    fun refreshIfStale(kind: MediaRootKind) {
        scope.launch {
            runCatching { scanMutexes.getValue(kind).withLock { scan(kind, force = false) } }
                .onFailure { Timber.w(it, "Media %s freshness check failed", kind.name) }
        }
    }

    /**
     * Reconciles [kind]'s library rows with its configured roots and scans each one, suspending
     * until the whole section is done. For callers that drive their own UI (the Settings
     * screens): [onProgress] receives human-readable progress, and the return value is the
     * finished message to show.
     */
    suspend fun scanAllRoots(
        kind: MediaRootKind,
        force: Boolean = true,
        onProgress: (String) -> Unit = {},
    ): String {
        if (mediaRootRepository.getAll(kind).isEmpty()) return "Add a root folder first."
        return scanMutexes.getValue(kind).withLock { scan(kind, force, onProgress) }
    }

    private fun launchScan(kind: MediaRootKind, force: Boolean): Job = scope.launch {
        runCatching { scanMutexes.getValue(kind).withLock { scan(kind, force) } }
            .onFailure { Timber.w(it, "Media %s scan failed", kind.name) }
    }

    private suspend fun scan(
        kind: MediaRootKind,
        force: Boolean,
        onProgress: (String) -> Unit = {},
    ): String {
        val roots = mediaRootRepository.getAll(kind)
        var total = 0
        var failure: String? = null
        when (kind) {
            MediaRootKind.MUSIC -> {
                dropOrphanMusicLibraries(roots)
                roots.forEach { root ->
                    val outcome = scanMusicFolder(musicFolderForRoot(root), force, onProgress)
                    total += outcome.count
                    failure = failure ?: outcome.error
                }
            }
            MediaRootKind.PHOTO -> {
                dropOrphanPhotoLibraries(roots)
                roots.forEach { root ->
                    val outcome = scanPhotoLibrary(photoLibraryForRoot(root), force, onProgress)
                    total += outcome.count
                    failure = failure ?: outcome.error
                }
            }
            MediaRootKind.VIDEO -> {
                dropOrphanVideoLibraries(roots)
                roots.forEach { root ->
                    val outcome = scanVideoLibrary(videoLibraryForRoot(root), force, onProgress)
                    total += outcome.count
                    failure = failure ?: outcome.error
                }
            }
        }
        return failure ?: "Found " + total + " " + kind.noun(total) + " across " + roots.size + " root(s)."
    }

    /** What one library's scan produced: item count, or the message if it failed. */
    private data class Outcome(val count: Int, val error: String?)

    // Library rows are keyed by their root's tree URI; a root removed from the configured list
    // (in the wizard or Settings) takes its library row with it on the next scan pass.
    private suspend fun dropOrphanMusicLibraries(roots: List<String>) {
        musicRepository.getFolders()
            .filter { it.treeUri !in roots }
            .forEach { musicRepository.removeFolder(it.id) }
    }

    private suspend fun dropOrphanPhotoLibraries(roots: List<String>) {
        photoRepository.getLibraries()
            .filter { it.treeUri !in roots }
            .forEach { photoRepository.removeLibrary(it.id) }
    }

    private suspend fun dropOrphanVideoLibraries(roots: List<String>) {
        videoRepository.getLibraries()
            .filter { it.treeUri !in roots }
            .forEach { videoRepository.removeLibrary(it.id) }
    }

    // Re-reads the row after creating it, so a scan always sees the PERSISTED values — including
    // the stored signature, which the shell returned by add* does not carry.
    private suspend fun musicFolderForRoot(root: String): MusicFolder {
        val existing = musicRepository.getFolders().firstOrNull { it.treeUri == root }
        val folder = existing ?: musicRepository.addFolder(displayName(root, "Music"), root)
        return musicRepository.getFolder(folder.id) ?: folder
    }

    private suspend fun photoLibraryForRoot(root: String): PhotoLibrary {
        val existing = photoRepository.getLibraries().firstOrNull { it.treeUri == root }
        val library = existing
            ?: photoRepository.addLibrary(displayName(root, "Photos"), root, scanRecursively = true)
        return photoRepository.getLibrary(library.id) ?: library
    }

    private suspend fun videoLibraryForRoot(root: String): VideoLibrary {
        val existing = videoRepository.getLibraries().firstOrNull { it.treeUri == root }
        val library = existing
            ?: videoRepository.addLibrary(displayName(root, "Videos"), root, scanRecursively = true)
        return videoRepository.getLibrary(library.id) ?: library
    }

    // ── The one scan pipeline per kind ──────────────────────────────────────────
    // Every entry point funnels through these three. They own the notification, the signature
    // gate and the write, so nothing else has to reimplement collect-and-replace.

    private suspend fun scanMusicFolder(
        target: MusicFolder,
        force: Boolean,
        onProgress: (String) -> Unit = {},
    ): Outcome {
        val taskId = "music_scan_" + target.id
        val report = progressFor(force)
        report?.running(taskId, "Scanning " + target.displayName, null)
        val existing = musicRepository.getTracksForFolder(target.id)
        var outcome = Outcome(target.trackCount, null)
        musicScanner.scan(
            folder = target,
            deep = false,
            existing = existing,
            knownSignature = if (force) null else target.scanSignature,
        ).collect { result ->
            when (result) {
                is MusicScanResult.Progress -> onProgress(result.tracksFound.toString() + " tracks")
                // Only ever reached on an automatic pass (a forced scan sends no known
                // signature, so the scanner cannot short-circuit). Nothing happened —
                // nothing to say.
                is MusicScanResult.Unchanged -> Unit
                is MusicScanResult.Complete -> {
                    musicRepository.replaceTracksForFolder(
                        result.folderId,
                        result.tracks,
                        System.currentTimeMillis(),
                        result.signature,
                    )
                    outcome = Outcome(result.tracks.size, null)
                    report?.complete(
                        taskId,
                        "Scanned " + target.displayName,
                        result.tracks.size.toString() + " tracks",
                    )
                }
                is MusicScanResult.Error -> {
                    outcome = Outcome(0, result.message)
                    // Always surfaced, automatic or not: "Permission lost, re-select
                    // folder." is the one message a silent library needs to show.
                    notifier.failed(taskId, "Scan failed", result.message)
                }
            }
        }
        return outcome
    }

    private suspend fun scanPhotoLibrary(
        target: PhotoLibrary,
        force: Boolean,
        onProgress: (String) -> Unit = {},
    ): Outcome {
        val taskId = "photo_scan_" + target.id
        val report = progressFor(force)
        report?.running(taskId, "Scanning " + target.displayName, null)
        var outcome = Outcome(target.photoCount, null)
        photoScanner.scan(
            library = target,
            deep = false,
            existing = photoRepository.getPhotosForLibrary(target.id),
            knownSignature = if (force) null else target.scanSignature,
        ).collect { result ->
            when (result) {
                is PhotoScanResult.Progress -> onProgress(result.photosFound.toString() + " photos")
                // Only ever reached on an automatic pass (a forced scan sends no known
                // signature, so the scanner cannot short-circuit). Nothing happened —
                // nothing to say.
                is PhotoScanResult.Unchanged -> Unit
                is PhotoScanResult.Complete -> {
                    photoRepository.replacePhotosForLibrary(
                        result.libraryId,
                        result.photos,
                        System.currentTimeMillis(),
                        result.signature,
                    )
                    outcome = Outcome(result.photos.size, null)
                    report?.complete(
                        taskId,
                        "Scanned " + target.displayName,
                        result.photos.size.toString() + " photos",
                    )
                }
                is PhotoScanResult.Error -> {
                    outcome = Outcome(0, result.message)
                    // Always surfaced, automatic or not: "Permission lost, re-select
                    // folder." is the one message a silent library needs to show.
                    notifier.failed(taskId, "Scan failed", result.message)
                }
            }
        }
        return outcome
    }

    private suspend fun scanVideoLibrary(
        target: VideoLibrary,
        force: Boolean,
        onProgress: (String) -> Unit = {},
    ): Outcome {
        val taskId = "video_scan_" + target.id
        val report = progressFor(force)
        report?.running(taskId, "Scanning " + target.displayName, null)
        var outcome = Outcome(target.videoCount, null)
        videoScanner.scan(
            library = target,
            deep = false,
            existing = videoRepository.getVideosForLibrary(target.id),
            knownSignature = if (force) null else target.scanSignature,
        ).collect { result ->
            when (result) {
                is VideoScanResult.Progress -> onProgress(result.videosFound.toString() + " videos")
                // Only ever reached on an automatic pass (a forced scan sends no known
                // signature, so the scanner cannot short-circuit). Nothing happened —
                // nothing to say.
                is VideoScanResult.Unchanged -> Unit
                is VideoScanResult.Complete -> {
                    videoRepository.replaceVideosForLibrary(
                        result.libraryId,
                        result.videos,
                        System.currentTimeMillis(),
                        result.signature,
                    )
                    outcome = Outcome(result.videos.size, null)
                    report?.complete(
                        taskId,
                        "Scanned " + target.displayName,
                        result.videos.size.toString() + " videos",
                    )
                }
                is VideoScanResult.Error -> {
                    outcome = Outcome(0, result.message)
                    // Always surfaced, automatic or not: "Permission lost, re-select
                    // folder." is the one message a silent library needs to show.
                    notifier.failed(taskId, "Scan failed", result.message)
                }
            }
        }
        return outcome
    }

    private fun MediaRootKind.noun(count: Int): String = when (this) {
        MediaRootKind.MUSIC -> if (count == 1) "track" else "tracks"
        MediaRootKind.PHOTO -> if (count == 1) "photo" else "photos"
        MediaRootKind.VIDEO -> if (count == 1) "video" else "videos"
    }

    private fun displayName(treeUri: String, fallback: String): String =
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.name }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: Uri.parse(treeUri).lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: fallback
}
