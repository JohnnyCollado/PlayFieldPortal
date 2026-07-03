package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.repository.MediaRootKind
import com.playfieldportal.core.data.repository.MediaRootRepository
import com.playfieldportal.core.domain.model.PhotoLibrary
import com.playfieldportal.core.domain.repository.PhotoRepository
import com.playfieldportal.core.ui.notification.BackgroundTaskNotifier
import com.playfieldportal.feature.library.scanner.PhotoScanResult
import com.playfieldportal.feature.library.scanner.PhotoScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PhotoSettingsUiState(
    val rootUri: String? = null,
    val rootName: String? = null,
    val scanning: Boolean = false,
    val scanMessage: String? = null,
) {
    val hasRoot: Boolean get() = rootUri != null
}

/**
 * Single-root Photo settings: one configurable root folder whose subtree is the Photo library.
 * Setting a root replaces any previous one and rescans; the library updates automatically.
 */
@HiltViewModel
class PhotoSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoRepository: PhotoRepository,
    private val photoScanner: PhotoScanner,
    private val mediaRootRepository: MediaRootRepository,
) : ViewModel() {

    private val notifier = BackgroundTaskNotifier(context)
    private val _ui = MutableStateFlow(PhotoSettingsUiState())
    val uiState: StateFlow<PhotoSettingsUiState> = _ui

    init {
        viewModelScope.launch {
            mediaRootRepository.observe(MediaRootKind.PHOTO).collect { uri ->
                _ui.value = _ui.value.copy(rootUri = uri, rootName = uri?.let(::displayName))
            }
        }
    }

    /** Sets (or replaces) the single root folder, persists access, and rescans. */
    fun setRoot(treeUri: Uri) {
        viewModelScope.launch {
            mediaRootRepository.persist(treeUri)
            mediaRootRepository.set(MediaRootKind.PHOTO, treeUri.toString())
            rescan()
        }
    }

    /** Rebuilds the single library from the root and scans it (fast incremental). */
    fun rescan() {
        viewModelScope.launch {
            val root = mediaRootRepository.get(MediaRootKind.PHOTO)
            if (root == null) {
                _ui.value = _ui.value.copy(scanMessage = "Add a root folder first.")
                return@launch
            }
            _ui.value = _ui.value.copy(scanning = true, scanMessage = "Scanning…")
            val library = syncSingleLibrary(root)
            val existing = photoRepository.getPhotosForLibrary(library.id)
            val taskId = "photo_scan_${library.id}"
            notifier.running(taskId, "Scanning ${library.displayName}", null)
            var total = 0
            var error: String? = null
            photoScanner.scan(library, deep = false, existing = existing).collect { result ->
                when (result) {
                    is PhotoScanResult.Progress ->
                        _ui.value = _ui.value.copy(scanMessage = "${result.photosFound} photos")
                    is PhotoScanResult.Complete -> {
                        photoRepository.replacePhotosForLibrary(result.libraryId, result.photos, System.currentTimeMillis())
                        total = result.photos.size
                        notifier.complete(taskId, "Scanned ${library.displayName}", "$total photos")
                    }
                    is PhotoScanResult.Error -> {
                        error = result.message
                        notifier.failed(taskId, "Scan failed", result.message)
                    }
                }
            }
            _ui.value = _ui.value.copy(scanning = false, scanMessage = error ?: "Found $total photos.")
        }
    }

    fun clearThumbnailCache() {
        viewModelScope.launch {
            val removed = photoScanner.clearThumbnailCache()
            _ui.value = _ui.value.copy(scanMessage = "Cleared $removed cached thumbnail(s). Rescan to regenerate.")
        }
    }

    fun dismissMessage() { _ui.value = _ui.value.copy(scanMessage = null) }

    // Ensures exactly one PhotoLibrary exists for [root] (recursive), removing any others.
    private suspend fun syncSingleLibrary(root: String): PhotoLibrary {
        val libs = photoRepository.getLibraries()
        val existing = libs.firstOrNull { it.treeUri == root }
        val library = existing ?: photoRepository.addLibrary(displayName(root), root, scanRecursively = true)
        libs.filter { it.id != library.id }.forEach { photoRepository.removeLibrary(it.id) }
        return photoRepository.getLibrary(library.id) ?: library
    }

    private fun displayName(treeUri: String): String =
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.name }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: Uri.parse(treeUri).lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: "Photos"
}
