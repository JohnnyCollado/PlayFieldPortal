package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.repository.FolderLinkStatus
import com.playfieldportal.core.data.repository.MediaRootKind
import com.playfieldportal.core.data.repository.MediaRootRepository
import com.playfieldportal.core.data.repository.SafGrants
import com.playfieldportal.core.domain.model.PhotoLibrary
import com.playfieldportal.core.domain.repository.PhotoRepository
import com.playfieldportal.core.ui.notification.BackgroundTaskNotifier
import com.playfieldportal.feature.library.scanner.PhotoScanResult
import com.playfieldportal.feature.library.scanner.PhotoScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PhotoSettingsUiState(
    // Every configured root, with its live SAF-grant status (same rows as Library Manager's
    // ROM Root Access — a photo library can span internal storage plus an SD card).
    val roots: List<RootFolderRow> = emptyList(),
    val scanning: Boolean = false,
    val scanMessage: String? = null,
) {
    val hasRoots: Boolean get() = roots.isNotEmpty()
}

/**
 * Multi-root Photo settings, mirroring Library Manager's ROM Root Access: several root folders
 * (each a persisted SAF grant whose subfolders become libraries) and a rescan that reconciles the
 * library rows with the configured roots and scans each root.
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
            // distinctUntilChanged: the backing DataStore is app-wide; without it every unrelated
            // preference write would re-run the persisted-grant snapshot below.
            mediaRootRepository.roots(MediaRootKind.PHOTO).distinctUntilChanged().collect { roots ->
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
    }

    /** Grants (and persists) a new root, adds it to the list, and rescans. */
    fun addRoot(treeUri: Uri) {
        viewModelScope.launch {
            mediaRootRepository.persist(treeUri)
            mediaRootRepository.add(MediaRootKind.PHOTO, treeUri.toString())
            rescan()
        }
    }

    /** Removes a root; its library row is dropped on the next rescan. */
    fun removeRoot(treeUri: String) {
        viewModelScope.launch {
            mediaRootRepository.remove(MediaRootKind.PHOTO, treeUri)
            rescan()
        }
    }

    /** Replaces one root's URI (re-link after a lost grant, or picking a different folder). */
    fun relinkRoot(oldTreeUri: String, newUri: Uri) {
        viewModelScope.launch {
            mediaRootRepository.persist(newUri)
            mediaRootRepository.replace(MediaRootKind.PHOTO, oldTreeUri, newUri.toString())
            rescan()
        }
    }

    /**
     * Reconciles the library rows with the configured roots (dropping rows whose root is gone)
     * and scans every root incrementally.
     */
    fun rescan() {
        viewModelScope.launch {
            val roots = mediaRootRepository.getAll(MediaRootKind.PHOTO)
            if (roots.isEmpty()) {
                _ui.value = _ui.value.copy(scanMessage = "Add a root folder first.")
                return@launch
            }
            _ui.value = _ui.value.copy(scanning = true, scanMessage = "Scanning…")

            // Roots removed in the wizard or here take their library rows with them.
            photoRepository.getLibraries()
                .filter { it.treeUri !in roots }
                .forEach { photoRepository.removeLibrary(it.id) }

            var total = 0
            var error: String? = null
            for (root in roots) {
                val library = syncLibraryForRoot(root)
                val existing = photoRepository.getPhotosForLibrary(library.id)
                val taskId = "photo_scan_${library.id}"
                notifier.running(taskId, "Scanning ${library.displayName}", null)
                photoScanner.scan(library, deep = false, existing = existing).collect { result ->
                    when (result) {
                        is PhotoScanResult.Progress ->
                            _ui.value = _ui.value.copy(scanMessage = "${result.photosFound} photos")
                        is PhotoScanResult.Complete -> {
                            photoRepository.replacePhotosForLibrary(result.libraryId, result.photos, System.currentTimeMillis())
                            total += result.photos.size
                            notifier.complete(taskId, "Scanned ${library.displayName}", "${result.photos.size} photos")
                        }
                        is PhotoScanResult.Error -> {
                            error = result.message
                            notifier.failed(taskId, "Scan failed", result.message)
                        }
                    }
                }
            }
            _ui.value = _ui.value.copy(scanning = false, scanMessage = error ?: "Found $total photos across ${roots.size} root(s).")
        }
    }

    fun clearThumbnailCache() {
        viewModelScope.launch {
            val removed = photoScanner.clearThumbnailCache()
            _ui.value = _ui.value.copy(scanMessage = "Cleared $removed cached thumbnail(s). Rescan to regenerate.")
        }
    }

    fun dismissMessage() { _ui.value = _ui.value.copy(scanMessage = null) }

    // Ensures one PhotoLibrary exists for [root] (recursive) — other roots keep their own rows.
    private suspend fun syncLibraryForRoot(root: String): PhotoLibrary {
        val existing = photoRepository.getLibraries().firstOrNull { it.treeUri == root }
        val library = existing ?: photoRepository.addLibrary(displayName(root), root, scanRecursively = true)
        return photoRepository.getLibrary(library.id) ?: library
    }

    private fun displayName(treeUri: String): String =
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.name }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: Uri.parse(treeUri).lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: "Photos"
}
