package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.video.VideoIntentResolver
import com.playfieldportal.core.data.video.VideoPlayerApp
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
import timber.log.Timber
import java.io.File
import javax.inject.Inject

// Sentinel pref values for the default player (see VideoRepository).
private const val PLAYER_BUILTIN = "builtin"
private const val PLAYER_ASK = "ask"

data class VideoSettingsUiState(
    val libraries: List<VideoLibrary> = emptyList(),
    val scanning: Boolean = false,
    val scanMessage: String? = null,
    val renameTarget: VideoLibrary? = null,
    // Default player: null/"builtin" = built-in, "ask" = chooser, else a package name.
    val defaultPlayer: String? = null,
    val availablePlayers: List<VideoPlayerApp> = emptyList(),
    val showPlayerPicker: Boolean = false,
) {
    val defaultPlayerLabel: String
        get() = when (defaultPlayer) {
            null, PLAYER_BUILTIN -> "Built-in"
            PLAYER_ASK           -> "Ask Every Time"
            else -> availablePlayers.firstOrNull { it.packageName == defaultPlayer }?.label ?: defaultPlayer
        }
}

@HiltViewModel
class VideoSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val videoRepository: VideoRepository,
    private val videoScanner: VideoScanner,
    private val intentResolver: VideoIntentResolver,
) : ViewModel() {

    private val notifier = BackgroundTaskNotifier(context)
    private val _ui = MutableStateFlow(VideoSettingsUiState())
    val uiState: StateFlow<VideoSettingsUiState> = _ui

    init {
        viewModelScope.launch {
            videoRepository.observeLibraries().collect { libs ->
                _ui.value = _ui.value.copy(libraries = libs)
            }
        }
        viewModelScope.launch {
            videoRepository.observeDefaultVideoPlayer().collect { pref ->
                _ui.value = _ui.value.copy(defaultPlayer = pref)
            }
        }
    }

    // ── Default player ──────────────────────────────────────────────────────────

    fun openPlayerPicker() {
        _ui.value = _ui.value.copy(showPlayerPicker = true, availablePlayers = intentResolver.availablePlayers())
    }

    fun dismissPlayerPicker() { _ui.value = _ui.value.copy(showPlayerPicker = false) }

    /** value: null = built-in, "ask" = chooser, else a package name. */
    fun chooseDefaultPlayer(value: String?) {
        _ui.value = _ui.value.copy(showPlayerPicker = false)
        viewModelScope.launch { videoRepository.setDefaultVideoPlayer(value) }
    }

    /** Called with the tree uri from ACTION_OPEN_DOCUMENT_TREE; persists read access and deep-scans. */
    fun addLibrary(treeUri: Uri) {
        viewModelScope.launch {
            if (!persistReadPermission(treeUri)) {
                _ui.value = _ui.value.copy(scanMessage = "Could not keep access to that folder. Try again.")
                return@launch
            }
            val name = runCatching { DocumentFile.fromTreeUri(context, treeUri)?.name }
                .getOrNull()?.takeIf { it.isNotBlank() }
                ?: treeUri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                ?: "Video Library"
            val library = videoRepository.addLibrary(name, treeUri.toString())
            scan(listOf(library), deep = true)
        }
    }

    fun quickScanAll() = viewModelScope.launch { scan(_ui.value.libraries.filter { it.enabled }, deep = false) }
    fun deepScanAll() = viewModelScope.launch { scan(_ui.value.libraries.filter { it.enabled }, deep = true) }

    fun scanLibrary(id: String, deep: Boolean) = viewModelScope.launch {
        videoRepository.getLibrary(id)?.let { scan(listOf(it), deep) }
    }

    fun removeLibrary(id: String) = viewModelScope.launch { videoRepository.removeLibrary(id) }

    fun beginRename(library: VideoLibrary) { _ui.value = _ui.value.copy(renameTarget = library) }
    fun cancelRename() { _ui.value = _ui.value.copy(renameTarget = null) }
    fun confirmRename(name: String) {
        val target = _ui.value.renameTarget ?: return
        _ui.value = _ui.value.copy(renameTarget = null)
        if (name.isBlank()) return
        viewModelScope.launch { videoRepository.renameLibrary(target.id, name.trim()) }
    }

    fun clearThumbnailCache() = viewModelScope.launch {
        val dir = File(context.filesDir, "video_thumbs")
        val removed = runCatching { dir.listFiles()?.count { it.delete() } ?: 0 }.getOrDefault(0)
        _ui.value = _ui.value.copy(scanMessage = "Cleared $removed cached thumbnail(s). Deep scan to regenerate.")
    }

    fun dismissMessage() { _ui.value = _ui.value.copy(scanMessage = null) }

    // ── Scanning ────────────────────────────────────────────────────────────────

    private suspend fun scan(libraries: List<VideoLibrary>, deep: Boolean) {
        if (libraries.isEmpty()) {
            _ui.value = _ui.value.copy(scanMessage = "No libraries to scan.")
            return
        }
        _ui.value = _ui.value.copy(scanning = true, scanMessage = "Scanning…")
        var totalVideos = 0
        var errors = 0
        for (library in libraries) {
            val taskId = "video_scan_${library.id}"
            val existing = videoRepository.getVideosForLibrary(library.id)
            notifier.running(taskId, "Scanning ${library.displayName}", null)
            videoScanner.scan(library, deep, existing).collect { result ->
                when (result) {
                    is VideoScanResult.Progress -> {
                        notifier.running(taskId, "Scanning ${result.libraryName}", null)
                        _ui.value = _ui.value.copy(scanMessage = "${result.libraryName}: ${result.videosFound} videos")
                    }
                    is VideoScanResult.Complete -> {
                        videoRepository.replaceVideosForLibrary(result.libraryId, result.videos, System.currentTimeMillis())
                        totalVideos += result.videos.size
                        notifier.complete(taskId, "Scanned ${library.displayName}", "${result.videos.size} videos")
                    }
                    is VideoScanResult.Error -> {
                        errors++
                        notifier.failed(taskId, "Scan failed: ${library.displayName}", result.message)
                        _ui.value = _ui.value.copy(scanMessage = result.message)
                    }
                }
            }
        }
        _ui.value = _ui.value.copy(
            scanning = false,
            scanMessage = if (errors > 0) "Done with $errors error(s). Found $totalVideos videos."
                          else "Found $totalVideos videos.",
        )
    }

    private fun persistReadPermission(uri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    }.getOrElse { e -> Timber.w(e, "Failed to persist read permission for $uri"); false }
}
