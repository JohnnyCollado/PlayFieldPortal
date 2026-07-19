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
 * Runs the first library scan for a media root picked in the SETUP WIZARD.
 *
 * The settings screens pair "set root" with an immediate rescan, which is what creates the
 * single library row and stamps its lastScannedAt — the signal the XMB's "+ Add" getting-started
 * rows key off. The wizard only persisted the root, so wizard-configured sections kept their
 * Add rows (and stayed empty) until the user found Rescan in Settings.
 *
 * Each scan mirrors the corresponding settings flow (Music/Photo/VideoSettingsViewModel.rescan)
 * minus the per-screen UI state: sync the single library row for the root, scan incrementally,
 * replace the library contents, and report through the shared background-task notifications.
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
        val root = mediaRootRepository.get(kind) ?: return
        when (kind) {
            MediaRootKind.MUSIC -> scanMusic(root)
            MediaRootKind.PHOTO -> scanPhoto(root)
            MediaRootKind.VIDEO -> scanVideo(root)
        }
    }

    private suspend fun scanMusic(root: String) {
        val folders = musicRepository.getFolders()
        val existingRow = folders.firstOrNull { it.treeUri == root }
        val folder = existingRow ?: musicRepository.addFolder(displayName(root, "Music"), root)
        folders.filter { it.id != folder.id }.forEach { musicRepository.removeFolder(it.id) }
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
        libs.filter { it.id != library.id }.forEach { photoRepository.removeLibrary(it.id) }
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
        libs.filter { it.id != library.id }.forEach { videoRepository.removeLibrary(it.id) }
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
