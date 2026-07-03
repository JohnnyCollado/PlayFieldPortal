package com.playfieldportal.feature.settings.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.repository.FolderAccessItem
import com.playfieldportal.core.data.repository.FolderAccessManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class FolderAccessUiState(
    val items: List<FolderAccessItem> = emptyList(),
    val loading: Boolean = true,
    val message: String? = null,
)

/**
 * Drives the Folder Access screen: lists every stored SAF folder (ROM root + media libraries) with
 * its live link status, and re-establishes access one folder at a time. Re-linking opens the system
 * tree picker pre-pointed at the saved location, so recovery after a restore is one tap per folder.
 */
@HiltViewModel
class FolderAccessViewModel @Inject constructor(
    private val folderAccessManager: FolderAccessManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FolderAccessUiState())
    val uiState: StateFlow<FolderAccessUiState> = _uiState.asStateFlow()

    // What the currently-open system picker is for, so its result can be applied correctly.
    private sealed interface Pending {
        data class Relink(val item: FolderAccessItem) : Pending
        object SetRoot : Pending
    }
    private var pending: Pending? = null

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val items = folderAccessManager.folders()
            _uiState.update { it.copy(items = items, loading = false) }
        }
    }

    /** Called before launching the folder picker to re-link an existing folder; returns initial URI. */
    fun beginRelink(item: FolderAccessItem): Uri? {
        pending = Pending.Relink(item)
        return runCatching { Uri.parse(item.treeUri) }.getOrNull()
    }

    /** Called before launching the folder picker to set the ROM root for the first time. */
    fun beginSetRoot() {
        pending = Pending.SetRoot
    }

    fun onPickerResult(uri: Uri?) {
        val action = pending
        pending = null
        if (uri == null || action == null) return
        viewModelScope.launch {
            val message = when (action) {
                is Pending.Relink -> {
                    runCatching { folderAccessManager.relink(action.item, uri) }
                        .onFailure { Timber.w(it, "Re-link failed for ${action.item.displayName}") }
                    "${action.item.displayName} re-linked. Re-scan it to refresh its contents."
                }
                Pending.SetRoot -> {
                    runCatching { folderAccessManager.addRomRoot(uri) }
                        .onFailure { Timber.w(it, "Add ROM root failed") }
                    "ROM Root added. In Settings → Library, tap Auto-Detect to load your consoles."
                }
            }
            _uiState.update { it.copy(items = folderAccessManager.folders(), message = message) }
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }
}
