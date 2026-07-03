package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.video.VideoIntentResolver
import com.playfieldportal.core.data.video.VideoPlayerApp
import com.playfieldportal.core.data.repository.MediaRootKind
import com.playfieldportal.core.data.repository.MediaRootRepository
import com.playfieldportal.core.domain.model.VideoLibrary
import com.playfieldportal.core.domain.repository.VideoRepository
import com.playfieldportal.core.ui.notification.BackgroundTaskNotifier
import com.playfieldportal.feature.library.scanner.VideoScanResult
import com.playfieldportal.feature.library.scanner.VideoScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// Sentinel pref values for the default player (see VideoRepository).
private const val PLAYER_BUILTIN = "builtin"   // Play Field Portal (built-in Media3)
private const val PLAYER_ASK = "ask"            // System Default (OS chooser each time)

data class VideoSettingsUiState(
    val rootUri: String? = null,
    val rootName: String? = null,
    val scanning: Boolean = false,
    val scanMessage: String? = null,
    // Default player: null/"builtin" = built-in, "ask" = system chooser, else a package name.
    val defaultPlayer: String? = null,
    val availablePlayers: List<VideoPlayerApp> = emptyList(),
    val showPlayerPicker: Boolean = false,
) {
    val hasRoot: Boolean get() = rootUri != null

    val defaultPlayerLabel: String
        get() = when (defaultPlayer) {
            null, PLAYER_BUILTIN -> "Play Field Portal"
            PLAYER_ASK           -> "System Default"
            else -> availablePlayers.firstOrNull { it.packageName == defaultPlayer }?.label ?: defaultPlayer
        }
}

/**
 * Single-root Video settings: one root folder whose subtree is the Video library, a fast rescan,
 * and the default player (Play Field Portal / System Default / a chosen app).
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
            mediaRootRepository.observe(MediaRootKind.VIDEO).collect { uri ->
                _ui.value = _ui.value.copy(rootUri = uri, rootName = uri?.let(::displayName))
            }
        }
        viewModelScope.launch {
            videoRepository.observeDefaultVideoPlayer().collect { pref ->
                _ui.value = _ui.value.copy(defaultPlayer = pref)
            }
        }
    }

    /** Sets (or replaces) the single root folder, persists access, and rescans. */
    fun setRoot(treeUri: Uri) {
        viewModelScope.launch {
            mediaRootRepository.persist(treeUri)
            mediaRootRepository.set(MediaRootKind.VIDEO, treeUri.toString())
            rescan()
        }
    }

    fun rescan() {
        viewModelScope.launch {
            val root = mediaRootRepository.get(MediaRootKind.VIDEO)
            if (root == null) {
                _ui.value = _ui.value.copy(scanMessage = "Add a root folder first.")
                return@launch
            }
            _ui.value = _ui.value.copy(scanning = true, scanMessage = "Scanning…")
            val library = syncSingleLibrary(root)
            val existing = videoRepository.getVideosForLibrary(library.id)
            val taskId = "video_scan_${library.id}"
            notifier.running(taskId, "Scanning ${library.displayName}", null)
            var total = 0
            var error: String? = null
            videoScanner.scan(library, deep = false, existing = existing).collect { result ->
                when (result) {
                    is VideoScanResult.Progress ->
                        _ui.value = _ui.value.copy(scanMessage = "${result.videosFound} videos")
                    is VideoScanResult.Complete -> {
                        videoRepository.replaceVideosForLibrary(result.libraryId, result.videos, System.currentTimeMillis())
                        total = result.videos.size
                        notifier.complete(taskId, "Scanned ${library.displayName}", "$total videos")
                    }
                    is VideoScanResult.Error -> {
                        error = result.message
                        notifier.failed(taskId, "Scan failed", result.message)
                    }
                }
            }
            _ui.value = _ui.value.copy(scanning = false, scanMessage = error ?: "Found $total videos.")
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

    // Ensures exactly one VideoLibrary exists for [root] (recursive), removing any others.
    private suspend fun syncSingleLibrary(root: String): VideoLibrary {
        val libs = videoRepository.getLibraries()
        val existing = libs.firstOrNull { it.treeUri == root }
        val library = existing ?: videoRepository.addLibrary(displayName(root), root, scanRecursively = true)
        libs.filter { it.id != library.id }.forEach { videoRepository.removeLibrary(it.id) }
        return videoRepository.getLibrary(library.id) ?: library
    }

    private fun displayName(treeUri: String): String =
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.name }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: Uri.parse(treeUri).lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: "Videos"
}
