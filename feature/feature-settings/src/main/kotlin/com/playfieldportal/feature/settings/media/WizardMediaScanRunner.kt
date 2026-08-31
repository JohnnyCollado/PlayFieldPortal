package com.playfieldportal.feature.settings.media

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.playfieldportal.core.data.repository.MediaRootKind
import com.playfieldportal.core.data.repository.MediaRootRepository
import com.playfieldportal.core.domain.repository.MusicRepository
import com.playfieldportal.core.domain.repository.PhotoRepository
import com.playfieldportal.core.domain.repository.VideoRepository
import com.playfieldportal.core.ui.notification.BackgroundTaskNotifier
import com.playfieldportal.feature.library.scanner.MusicScanResult
import com.playfieldportal.feature.library.scanner.MusicScanner
import com.playfieldportal.feature.library.scanner.PhotoScanResult
import com.playfieldportal.feature.library.scanner.PhotoScanner
import com.playfieldportal.feature.library.scanner.VideoScanResult
import com.playfieldportal.feature.library.scanner.VideoScanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the library (re)scan for a media section after a root change in the SETUP WIZARD (or any
 * other root list edit).
 *
 * The settings screens pair every root change with an immediate rescan, which is what creates the
 * library rows and stamps their lastScannedAt — the signal the XMB's "+ Add" getting-started rows
 * key off. This runner is the shared pass: it RECONCILES each section's library rows with the
 * configured roots (creating a row per root, removing rows whose root was removed) and then scans
 * every configured root incrementally. Roots may be MULTIPLE — a music library can span internal
 * storage plus an SD card, exactly like ROM roots.
 *
 * Each scan mirrors the corresponding settings flow (Music/Photo/VideoSettingsViewModel.rescan)
 * minus the per-screen UI state, and reports through the shared background-task notifications.
 * Runs on its own application-scoped supervisor so a scan survives the wizard (and its
 * ViewModel) closing; one scan per kind at a time.
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifier = BackgroundTaskNotifier(context)
    private val inFlight = ConcurrentHashMap<MediaRootKind, Job>()

    /** Starts (or restarts after completion) the scan for [kind]'s current root. */
    fun kickoff(kind: MediaRootKind) {
        if (inFlight[kind]?.isActive == true) return
        inFlight[kind] = scope.launch {
            runCatching { scan(kind) }
                .onFailure { Timber.w(it, "Wizard %s scan failed", kind.name) }
        }
    }

    private suspend fun scan(kind: MediaRootKind) {
        val roots = mediaRootRepository.getAll(kind)
        when (kind) {
            MediaRootKind.MUSIC -> {
                dropOrphanMusicLibraries(roots); roots.forEach { scanMusic(it) }
            }
            MediaRootKind.PHOTO -> {
                dropOrphanPhotoLibraries(roots); roots.forEach { scanPhoto(it) }
            }
            MediaRootKind.VIDEO -> {
                dropOrphanVideoLibraries(roots); roots.forEach { scanVideo(it) }
            }
        }
    }

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

    private suspend fun scanMusic(root: String) {
        val folders = musicRepository.getFolders()
        val existingRow = folders.firstOrNull { it.treeUri == root }
        val folder = existingRow ?: musicRepository.addFolder(displayName(root, "Music"), root)
        val target = musicRepository.getFolder(folder.id) ?: folder

        val taskId = "music_scan_${target.id}"
        notifier.running(taskId, "Scanning ${target.displayName}", null)
        val existing = musicRepository.observeTracksByFolder(target.id).first()
        musicScanner.scan(target, deep = false, existing = existing).collect { result ->
            when (result) {
                is MusicScanResult.Progress -> Unit
                is MusicScanResult.Complete -> {
                    musicRepository.replaceTracksForFolder(result.folderId, result.tracks, System.currentTimeMillis())
                    notifier.complete(taskId, "Scanned ${target.displayName}", "${result.tracks.size} tracks")
                }
                is MusicScanResult.Error -> notifier.failed(taskId, "Scan failed", result.message)
            }
        }
    }

    private suspend fun scanPhoto(root: String) {
        val libs = photoRepository.getLibraries()
        val existingRow = libs.firstOrNull { it.treeUri == root }
        val library = existingRow ?: photoRepository.addLibrary(displayName(root, "Photos"), root, scanRecursively = true)
        val target = photoRepository.getLibrary(library.id) ?: library

        val taskId = "photo_scan_${target.id}"
        notifier.running(taskId, "Scanning ${target.displayName}", null)
        photoScanner.scan(target, deep = false, existing = photoRepository.getPhotosForLibrary(target.id)).collect { result ->
            when (result) {
                is PhotoScanResult.Progress -> Unit
                is PhotoScanResult.Complete -> {
                    photoRepository.replacePhotosForLibrary(result.libraryId, result.photos, System.currentTimeMillis())
                    notifier.complete(taskId, "Scanned ${target.displayName}", "${result.photos.size} photos")
                }
                is PhotoScanResult.Error -> notifier.failed(taskId, "Scan failed", result.message)
            }
        }
    }

    private suspend fun scanVideo(root: String) {
        val libs = videoRepository.getLibraries()
        val existingRow = libs.firstOrNull { it.treeUri == root }
        val library = existingRow ?: videoRepository.addLibrary(displayName(root, "Videos"), root, scanRecursively = true)
        val target = videoRepository.getLibrary(library.id) ?: library

        val taskId = "video_scan_${target.id}"
        notifier.running(taskId, "Scanning ${target.displayName}", null)
        videoScanner.scan(target, deep = false, existing = videoRepository.getVideosForLibrary(target.id)).collect { result ->
            when (result) {
                is VideoScanResult.Progress -> Unit
                is VideoScanResult.Complete -> {
                    videoRepository.replaceVideosForLibrary(result.libraryId, result.videos, System.currentTimeMillis())
                    notifier.complete(taskId, "Scanned ${target.displayName}", "${result.videos.size} videos")
                }
                is VideoScanResult.Error -> notifier.failed(taskId, "Scan failed", result.message)
            }
        }
    }

    private fun displayName(treeUri: String, fallback: String): String =
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.name }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: Uri.parse(treeUri).lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: fallback
}
