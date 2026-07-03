package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.music.MusicIntentResolver
import com.playfieldportal.core.data.music.MusicPlayerApp
import com.playfieldportal.core.data.repository.MediaRootKind
import com.playfieldportal.core.data.repository.MediaRootRepository
import com.playfieldportal.core.domain.model.MusicFolder
import com.playfieldportal.core.domain.repository.MusicRepository
import com.playfieldportal.core.ui.notification.BackgroundTaskNotifier
import com.playfieldportal.feature.library.scanner.MusicScanResult
import com.playfieldportal.feature.library.scanner.MusicScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MusicSettingsUiState(
    val rootUri: String? = null,
    val rootName: String? = null,
    val defaultPlayer: String? = null,
    val availablePlayers: List<MusicPlayerApp> = emptyList(),
    val scanning: Boolean = false,
    val scanMessage: String? = null,
    val showPlayerPicker: Boolean = false,
) {
    val hasRoot: Boolean get() = rootUri != null

    val defaultPlayerLabel: String
        get() = when (defaultPlayer) {
            MusicIntentResolver.BUILTIN -> "Play Field Portal"
            null -> "System Default"
            else -> availablePlayers.firstOrNull { it.packageName == defaultPlayer }?.label ?: defaultPlayer
        }
}

/**
 * Single-root Music settings: one root folder whose subtree is the Music library, a fast rescan,
 * and the default player (Play Field Portal / System Default / a chosen app).
 */
@HiltViewModel
class MusicSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val musicScanner: MusicScanner,
    private val intentResolver: MusicIntentResolver,
    private val mediaRootRepository: MediaRootRepository,
) : ViewModel() {

    private val notifier = BackgroundTaskNotifier(context)
    private val _ui = MutableStateFlow(MusicSettingsUiState())
    val uiState: StateFlow<MusicSettingsUiState> = _ui

    init {
        viewModelScope.launch {
            mediaRootRepository.observe(MediaRootKind.MUSIC).collect { uri ->
                _ui.update { it.copy(rootUri = uri, rootName = uri?.let(::displayName)) }
            }
        }
        viewModelScope.launch {
            musicRepository.observeDefaultPlayerPackage().collect { player ->
                _ui.update { it.copy(defaultPlayer = player) }
            }
        }
    }

    /** Sets (or replaces) the single root folder, persists access, and rescans. */
    fun setRoot(treeUri: Uri) {
        viewModelScope.launch {
            mediaRootRepository.persist(treeUri)
            mediaRootRepository.set(MediaRootKind.MUSIC, treeUri.toString())
            rescan()
        }
    }

    fun rescan() {
        viewModelScope.launch {
            val root = mediaRootRepository.get(MediaRootKind.MUSIC)
            if (root == null) {
                _ui.update { it.copy(scanMessage = "Add a root folder first.") }
                return@launch
            }
            _ui.update { it.copy(scanning = true, scanMessage = "Scanning…") }
            val folder = syncSingleFolder(root)
            val existing = musicRepository.observeTracksByFolder(folder.id).first()
            val taskId = "music_scan_${folder.id}"
            notifier.running(taskId, "Scanning ${folder.displayName}", null)
            var total = 0
            var error: String? = null
            musicScanner.scan(folder, deep = false, existing = existing).collect { result ->
                when (result) {
                    is MusicScanResult.Progress ->
                        _ui.update { it.copy(scanMessage = "${result.tracksFound} tracks") }
                    is MusicScanResult.Complete -> {
                        musicRepository.replaceTracksForFolder(result.folderId, result.tracks, System.currentTimeMillis())
                        total = result.tracks.size
                        notifier.complete(taskId, "Scanned ${folder.displayName}", "$total tracks")
                    }
                    is MusicScanResult.Error -> {
                        error = result.message
                        notifier.failed(taskId, "Scan failed", result.message)
                    }
                }
            }
            _ui.update { it.copy(scanning = false, scanMessage = error ?: "Found $total tracks.") }
        }
    }

    // ── Default player ──────────────────────────────────────────────────────────

    fun openPlayerPicker() =
        _ui.update { it.copy(showPlayerPicker = true, availablePlayers = intentResolver.availablePlayers()) }

    fun dismissPlayerPicker() = _ui.update { it.copy(showPlayerPicker = false) }

    /** [value] = [MusicIntentResolver.BUILTIN] (PFP), null (system default), or a package name. */
    fun chooseDefaultPlayer(value: String?) {
        _ui.update { it.copy(showPlayerPicker = false) }
        viewModelScope.launch { musicRepository.setDefaultPlayerPackage(value) }
    }

    fun dismissMessage() = _ui.update { it.copy(scanMessage = null) }

    // Ensures exactly one MusicFolder exists for [root], removing any others (single-root model).
    private suspend fun syncSingleFolder(root: String): MusicFolder {
        val folders = musicRepository.getFolders()
        val existing = folders.firstOrNull { it.treeUri == root }
        val folder = existing ?: musicRepository.addFolder(displayName(root), root)
        folders.filter { it.id != folder.id }.forEach { musicRepository.removeFolder(it.id) }
        return musicRepository.getFolder(folder.id) ?: folder
    }

    private fun displayName(treeUri: String): String =
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.name }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: Uri.parse(treeUri).lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: "Music"
}
