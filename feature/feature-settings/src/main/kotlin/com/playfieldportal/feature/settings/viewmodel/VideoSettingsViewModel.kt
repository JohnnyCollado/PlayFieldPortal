package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.video.VideoIntentResolver
import com.playfieldportal.core.data.video.VideoPlayerApp
import com.playfieldportal.core.data.repository.FolderLinkStatus
import com.playfieldportal.core.data.repository.MediaRootKind
import com.playfieldportal.core.data.repository.MediaRootRepository
import com.playfieldportal.core.data.repository.SafGrants
import com.playfieldportal.core.domain.model.VideoLibrary
import com.playfieldportal.core.domain.repository.VideoRepository
import com.playfieldportal.core.ui.notification.BackgroundTaskNotifier
import com.playfieldportal.feature.library.scanner.VideoScanResult
import com.playfieldportal.feature.library.scanner.VideoScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// Sentinel pref values for the default player (see VideoRepository).
private const val PLAYER_BUILTIN = "builtin"   // Play Field Portal (built-in Media3)
private const val PLAYER_ASK = "ask"           // System Default (OS chooser each time)

data class VideoSettingsUiState(
    // Every configured root, with its live SAF-grant status (same rows as Library Manager's
    // ROM Root Access — a video library can span internal storage plus an SD card).
    val roots: List<RootFolderRow> = emptyList(),
    val scanning: Boolean = false,
    val scanMessage: String? = null,
    // Default player: null/"builtin" = built-in, "ask" = system chooser, else a package name.
    val defaultPlayer: String? = null,
    val availablePlayers: List<VideoPlayerApp> = emptyList(),
    val showPlayerPicker: Boolean = false,
) {
    val hasRoots: Boolean get() = roots.isNotEmpty()

    val defaultPlayerLabel: String
        get() = when (defaultPlayer) {
            null, PLAYER_BUILTIN -> "Play Field Portal"
            PLAYER_ASK           -> "System Default"
            else -> availablePlayers.firstOrNull { it.packageName == defaultPlayer }?.label ?: defaultPlayer
        }
}

/**
 * Multi-root Video settings, mirroring Library Manager's ROM Root Access: several root folders per
 * section (each a persisted SAF grant whose subfolders become libraries), a rescan that reconciles
 * the library rows with the configured roots and scans each root, and the default player.
 */
@HiltViewModel
class VideoSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val videoRepository: VideoRepository,
    private val videoScanner: VideoScanner,
    private val intentResolver: VideoIntentResolver,
    private val mediaRootRepository: MediaRootRepository,
) : ViewModel() {

    private val notifier = BackgroundTaskNotifier(context)
    private val _ui = MutableStateFlow(VideoSettingsUiState())
    val uiState: StateFlow<VideoSettingsUiState> = _ui

    init {
        viewModelScope.launch {
            // distinctUntilChanged: the backing DataStore is app-wide; without it every unrelated
            // preference write would re-run the persisted-grant snapshot below.
            mediaRootRepository.roots(MediaRootKind.VIDEO).distinctUntilChanged().collect { roots ->
                val persisted = SafGrants.persistedReadUris(context.contentResolver)
                _ui.value = _ui.value.copy(roots = roots.map { uri ->
                    RootFolderRow(
                        treeUri = uri,
                        name = displayName(uri),
                        linked = SafGrants.linkStatus(uri, persisted) == FolderLinkStatus.LINKED,
                    )
                })
            }
        }
        viewModelScope.launch {
            videoRepository.observeDefaultVideoPlayer().collect { pref ->
                _ui.value = _ui.value.copy(defaultPlayer = pref)
            }
        }
    }

    /** Grants (and persists) a new root, adds it to the list, and rescans. */
    fun addRoot(treeUri: Uri) {
        viewModelScope.launch {
            mediaRootRepository.persist(treeUri)
            mediaRootRepository.add(MediaRootKind.VIDEO, treeUri.toString())
            rescan()
        }
    }

    /** Removes a root; its library row is dropped on the next rescan. */
    fun removeRoot(treeUri: String) {
        viewModelScope.launch {
            mediaRootRepository.remove(MediaRootKind.VIDEO, treeUri)
            rescan()
        }
    }

    /** Replaces one root's URI (re-link after a lost grant, or picking a different folder). */
    fun relinkRoot(oldTreeUri: String, newUri: Uri) {
        viewModelScope.launch {
            mediaRootRepository.persist(newUri)
            mediaRootRepository.replace(MediaRootKind.VIDEO, oldTreeUri, newUri.toString())
            rescan()
        }
    }

    /**
     * Reconciles the library rows with the configured roots (dropping rows whose root is gone)
     * and scans every root incrementally.
     */
    fun rescan() {
        viewModelScope.launch {
            val roots = mediaRootRepository.getAll(MediaRootKind.VIDEO)
            if (roots.isEmpty()) {
                _ui.value = _ui.value.copy(scanMessage = "Add a root folder first.")
                return@launch
            }
            _ui.value = _ui.value.copy(scanning = true, scanMessage = "Scanning…")

            // Roots removed in the wizard or here take their library rows with them.
            videoRepository.getLibraries()
                .filter { it.treeUri !in roots }
                .forEach { videoRepository.removeLibrary(it.id) }

            var total = 0
            var error: String? = null
            for (root in roots) {
                val library = syncLibraryForRoot(root)
                val existing = videoRepository.getVideosForLibrary(library.id)
                val taskId = "video_scan_${library.id}"
                notifier.running(taskId, "Scanning ${library.displayName}", null)
                videoScanner.scan(library, deep = false, existing = existing).collect { result ->
                    when (result) {
                        is VideoScanResult.Progress ->
                            _ui.value = _ui.value.copy(scanMessage = "${result.videosFound} videos")
                        is VideoScanResult.Complete -> {
                            videoRepository.replaceVideosForLibrary(result.libraryId, result.videos, System.currentTimeMillis())
                            total += result.videos.size
                            notifier.complete(taskId, "Scanned ${library.displayName}", "${result.videos.size} videos")
                        }
                        is VideoScanResult.Error -> {
                            error = result.message
                            notifier.failed(taskId, "Scan failed", result.message)
                        }
                    }
                }
            }
            _ui.value = _ui.value.copy(scanning = false, scanMessage = error ?: "Found $total videos across ${roots.size} root(s).")
        }
    }

    // ── Default player ──────────────────────────────────────────────────────────

    fun openPlayerPicker() {
        _ui.value = _ui.value.copy(showPlayerPicker = true, availablePlayers = intentResolver.availablePlayers())
    }

    fun dismissPlayerPicker() { _ui.value = _ui.value.copy(showPlayerPicker = false) }

    /** [value] = null/"builtin" (PFP), "ask" (system default), or a package name. */
    fun chooseDefaultPlayer(value: String?) {
        _ui.value = _ui.value.copy(showPlayerPicker = false)
        viewModelScope.launch { videoRepository.setDefaultVideoPlayer(value) }
    }

    fun dismissMessage() { _ui.value = _ui.value.copy(scanMessage = null) }

    // Ensures one VideoLibrary exists for [root] (recursive) — other roots keep their own rows.
    private suspend fun syncLibraryForRoot(root: String): VideoLibrary {
        val existing = videoRepository.getLibraries().firstOrNull { it.treeUri == root }
        val library = existing ?: videoRepository.addLibrary(displayName(root), root, scanRecursively = true)
        return videoRepository.getLibrary(library.id) ?: library
    }

    private fun displayName(treeUri: String): String =
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.name }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: Uri.parse(treeUri).lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: "Videos"
}
