package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.music.MusicIntentResolver
import com.playfieldportal.core.data.music.MusicPlayerApp
import com.playfieldportal.core.data.repository.FolderLinkStatus
import com.playfieldportal.core.data.repository.MediaRootKind
import com.playfieldportal.core.data.repository.MediaRootRepository
import com.playfieldportal.feature.settings.media.WizardMediaScanRunner
import com.playfieldportal.core.data.repository.SafGrants
import com.playfieldportal.core.domain.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MusicSettingsUiState(
    // Every configured root, with its live SAF-grant status (same rows as Library Manager's
    // ROM Root Access — a music library can span internal storage plus an SD card).
    val roots: List<RootFolderRow> = emptyList(),
    val defaultPlayer: String? = null,
    val availablePlayers: List<MusicPlayerApp> = emptyList(),
    val scanning: Boolean = false,
    val scanMessage: String? = null,
    val showPlayerPicker: Boolean = false,
) {
    val hasRoots: Boolean get() = roots.isNotEmpty()

    val defaultPlayerLabel: String
        get() = when (defaultPlayer) {
            MusicIntentResolver.BUILTIN -> "Play Field Portal"
            null -> "System Default"
            else -> availablePlayers.firstOrNull { it.packageName == defaultPlayer }?.label ?: defaultPlayer
        }
}

/**
 * Multi-root Music settings, mirroring Library Manager's ROM Root Access: several root folders per
 * section (each a persisted SAF grant whose subfolders become libraries), a rescan that reconciles
 * the library rows with the configured roots and scans each root, and the default player.
 */
@HiltViewModel
class MusicSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val intentResolver: MusicIntentResolver,
    private val mediaRootRepository: MediaRootRepository,
    private val scanRunner: WizardMediaScanRunner,
) : ViewModel() {

    private val _ui = MutableStateFlow(MusicSettingsUiState())
    val uiState: StateFlow<MusicSettingsUiState> = _ui

    init {
        viewModelScope.launch {
            // distinctUntilChanged: the backing DataStore is app-wide; without it every unrelated
            // preference write would re-run the persisted-grant snapshot below.
            mediaRootRepository.roots(MediaRootKind.MUSIC).distinctUntilChanged().collect { roots ->
                val persisted = SafGrants.persistedReadUris(context.contentResolver)
                _ui.update {
                    it.copy(roots = roots.map { uri ->
                        RootFolderRow(
                            treeUri = uri,
                            name = displayName(uri),
                            linked = SafGrants.linkStatus(uri, persisted) == FolderLinkStatus.LINKED,
                        )
                    })
                }
            }
        }
        viewModelScope.launch {
            musicRepository.observeDefaultPlayerPackage().collect { player ->
                _ui.update { it.copy(defaultPlayer = player) }
            }
        }
    }

    /** Grants (and persists) a new root, adds it to the list, and rescans. */
    fun addRoot(treeUri: Uri) {
        viewModelScope.launch {
            mediaRootRepository.persist(treeUri)
            mediaRootRepository.add(MediaRootKind.MUSIC, treeUri.toString())
            rescan()
        }
    }

    /** Removes a root; its library row is dropped on the next rescan. */
    fun removeRoot(treeUri: String) {
        viewModelScope.launch {
            mediaRootRepository.remove(MediaRootKind.MUSIC, treeUri)
            rescan()
        }
    }

    /** Replaces one root's URI (re-link after a lost grant, or picking a different folder). */
    fun relinkRoot(oldTreeUri: String, newUri: Uri) {
        viewModelScope.launch {
            mediaRootRepository.persist(newUri)
            mediaRootRepository.replace(MediaRootKind.MUSIC, oldTreeUri, newUri.toString())
            rescan()
        }
    }

    /**
     * Reconciles the library rows with the configured roots (dropping rows whose root is gone)
     * and scans every root incrementally.
     */
    fun rescan() {
        viewModelScope.launch {
            _ui.update { it.copy(scanning = true, scanMessage = "Scanning…") }
            val message = scanRunner.scanAllRoots(MediaRootKind.MUSIC, force = true) { progress ->
                _ui.update { it.copy(scanMessage = progress) }
            }
            _ui.update { it.copy(scanning = false, scanMessage = message) }
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

    // Label for a root row in the list. The library row's own name is the runner's
    // business; this is only what the user sees under "Root folders".
    private fun displayName(treeUri: String): String =
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.name }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: Uri.parse(treeUri).lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: "Music"
}
