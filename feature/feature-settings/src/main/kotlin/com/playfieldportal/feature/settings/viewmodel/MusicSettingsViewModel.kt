package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.music.MusicIntentResolver
import com.playfieldportal.core.data.music.MusicPlayerApp
import com.playfieldportal.core.domain.model.MusicFolder
import com.playfieldportal.core.domain.repository.MusicRepository
import com.playfieldportal.core.ui.notification.BackgroundTaskNotifier
import com.playfieldportal.feature.library.scanner.MusicScanResult
import com.playfieldportal.feature.library.scanner.MusicScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class MusicSettingsUiState(
    val folders: List<MusicFolder> = emptyList(),
    val defaultPlayerPackage: String? = null,
    val availablePlayers: List<MusicPlayerApp> = emptyList(),
    val scanning: Boolean = false,
    val scanMessage: String? = null,
    val renameTarget: MusicFolder? = null,
    val showPlayerPicker: Boolean = false,
) {
    val defaultPlayerLabel: String
        get() = defaultPlayerPackage
            ?.let { pkg -> availablePlayers.firstOrNull { it.packageName == pkg }?.label ?: pkg }
            ?: "System default"
}

@HiltViewModel
class MusicSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val musicScanner: MusicScanner,
    private val intentResolver: MusicIntentResolver,
) : ViewModel() {

    private val notifier = BackgroundTaskNotifier(context)
    private val _ui = MutableStateFlow(MusicSettingsUiState())
    val uiState: StateFlow<MusicSettingsUiState> = _ui

    init {
        viewModelScope.launch {
            combine(
                musicRepository.observeFolders(),
                musicRepository.observeDefaultPlayerPackage(),
            ) { folders, player -> folders to player }
                .collect { (folders, player) ->
                    _ui.update { it.copy(folders = folders, defaultPlayerPackage = player) }
                }
        }
    }

    // ── Folders ───────────────────────────────────────────────────────────────

    /** Called with the tree uri from ACTION_OPEN_DOCUMENT_TREE; persists read access and scans. */
    fun addFolder(treeUri: Uri) {
        viewModelScope.launch {
            val persisted = persistReadPermission(treeUri)
            if (!persisted) {
                _ui.update { it.copy(scanMessage = "Could not keep access to that folder. Try again.") }
                return@launch
            }
            val name = runCatching { DocumentFile.fromTreeUri(context, treeUri)?.name }
                .getOrNull()?.takeIf { it.isNotBlank() }
                ?: treeUri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                ?: "Music Folder"
            val folder = musicRepository.addFolder(name, treeUri.toString())
            scan(listOf(folder))
        }
    }

    fun scanAll() = viewModelScope.launch { scan(_ui.value.folders.filter { it.enabled }) }

    fun scanFolder(id: String) = viewModelScope.launch {
        musicRepository.getFolder(id)?.let { scan(listOf(it)) }
    }

    fun setEnabled(id: String, enabled: Boolean) =
        viewModelScope.launch { musicRepository.setFolderEnabled(id, enabled) }

    fun removeFolder(id: String) = viewModelScope.launch { musicRepository.removeFolder(id) }

    fun beginRename(folder: MusicFolder) = _ui.update { it.copy(renameTarget = folder) }
    fun cancelRename() = _ui.update { it.copy(renameTarget = null) }
    fun confirmRename(name: String) {
        val target = _ui.value.renameTarget ?: return
        _ui.update { it.copy(renameTarget = null) }
        if (name.isBlank()) return
        viewModelScope.launch { musicRepository.renameFolder(target.id, name.trim()) }
    }

    // ── Default player ──────────────────────────────────────────────────────────

    fun openPlayerPicker() {
        _ui.update { it.copy(showPlayerPicker = true, availablePlayers = intentResolver.availablePlayers()) }
    }

    fun dismissPlayerPicker() = _ui.update { it.copy(showPlayerPicker = false) }

    fun chooseDefaultPlayer(packageName: String?) {
        _ui.update { it.copy(showPlayerPicker = false) }
        viewModelScope.launch { musicRepository.setDefaultPlayerPackage(packageName) }
    }

    fun dismissMessage() = _ui.update { it.copy(scanMessage = null) }

    // ── Scanning ────────────────────────────────────────────────────────────────

    private suspend fun scan(folders: List<MusicFolder>) {
        if (folders.isEmpty()) {
            _ui.update { it.copy(scanMessage = "No folders to scan.") }
            return
        }
        _ui.update { it.copy(scanning = true, scanMessage = "Scanning…") }
        var totalTracks = 0
        var errors = 0
        for (folder in folders) {
            val taskId = "music_scan_${folder.id}"
            notifier.running(taskId, "Scanning ${folder.displayName}", null)
            musicScanner.scan(folder).collect { result ->
                when (result) {
                    is MusicScanResult.Progress -> {
                        notifier.running(taskId, "Scanning ${result.folderName}", null)
                        _ui.update { it.copy(scanMessage = "${result.folderName}: ${result.tracksFound} tracks") }
                    }
                    is MusicScanResult.Complete -> {
                        musicRepository.replaceTracksForFolder(
                            result.folderId, result.tracks, System.currentTimeMillis(),
                        )
                        totalTracks += result.tracks.size
                        notifier.complete(taskId, "Scanned ${folder.displayName}", "${result.tracks.size} tracks")
                    }
                    is MusicScanResult.Error -> {
                        errors++
                        notifier.failed(taskId, "Scan failed: ${folder.displayName}", result.message)
                        _ui.update { it.copy(scanMessage = result.message) }
                    }
                }
            }
        }
        _ui.update {
            it.copy(
                scanning = false,
                scanMessage = if (errors > 0) "Done with $errors error(s). Found $totalTracks tracks."
                              else "Found $totalTracks tracks.",
            )
        }
    }

    private fun persistReadPermission(uri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    }.getOrElse { e -> Timber.w(e, "Failed to persist read permission for $uri"); false }
}
