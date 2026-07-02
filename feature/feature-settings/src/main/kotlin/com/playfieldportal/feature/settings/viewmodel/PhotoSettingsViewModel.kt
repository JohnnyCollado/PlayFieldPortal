package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import timber.log.Timber
import java.io.File
import javax.inject.Inject

data class PhotoSettingsUiState(
    val libraries: List<PhotoLibrary> = emptyList(),
    val scanning: Boolean = false,
    val scanMessage: String? = null,
    val renameTarget: PhotoLibrary? = null,
    // Set while the folder picker is out to CHANGE an existing library's folder (vs adding a new one).
    val changeFolderTargetId: String? = null,
)

@HiltViewModel
class PhotoSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoRepository: PhotoRepository,
    private val photoScanner: PhotoScanner,
) : ViewModel() {

    private val notifier = BackgroundTaskNotifier(context)
    private val _ui = MutableStateFlow(PhotoSettingsUiState())
    val uiState: StateFlow<PhotoSettingsUiState> = _ui

    init {
        viewModelScope.launch {
            photoRepository.observeLibraries().collect { libs ->
                _ui.value = _ui.value.copy(libraries = libs)
            }
        }
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
                ?: "Photo Library"
            // Recursive by default — the scanner probes files with bounded parallelism, so nested
            // folders stay fast. "Include Subfolders" below can limit an Album to one folder.
            val library = photoRepository.addLibrary(name, treeUri.toString())
            scan(listOf(library), deep = true)
        }
    }

    /** Toggles subfolder scanning for one Album, then rescans so its contents match right away. */
    fun toggleRecursive(library: PhotoLibrary) {
        viewModelScope.launch {
            photoRepository.setLibraryScanRecursively(library.id, !library.scanRecursively)
            photoRepository.getLibrary(library.id)?.let { scan(listOf(it), deep = false) }
        }
    }

    // ── Change folder: the picker result is routed here while changeFolderTargetId is set ──

    fun beginChangeFolder(libraryId: String) {
        _ui.value = _ui.value.copy(changeFolderTargetId = libraryId)
    }

    fun cancelChangeFolder() {
        _ui.value = _ui.value.copy(changeFolderTargetId = null)
    }

    /** Repoints the pending library at [treeUri] and deep-scans it (photos are replaced). */
    fun changeFolder(treeUri: Uri) {
        val targetId = _ui.value.changeFolderTargetId ?: return
        _ui.value = _ui.value.copy(changeFolderTargetId = null)
        viewModelScope.launch {
            if (!persistReadPermission(treeUri)) {
                _ui.value = _ui.value.copy(scanMessage = "Could not keep access to that folder. Try again.")
                return@launch
            }
            photoRepository.setLibraryTreeUri(targetId, treeUri.toString())
            photoRepository.getLibrary(targetId)?.let { scan(listOf(it), deep = true) }
        }
    }

    fun quickScanAll() = viewModelScope.launch { scan(_ui.value.libraries.filter { it.enabled }, deep = false) }
    fun deepScanAll() = viewModelScope.launch { scan(_ui.value.libraries.filter { it.enabled }, deep = true) }

    fun scanLibrary(id: String, deep: Boolean) = viewModelScope.launch {
        photoRepository.getLibrary(id)?.let { scan(listOf(it), deep) }
    }

    fun removeLibrary(id: String) = viewModelScope.launch { photoRepository.removeLibrary(id) }

    fun beginRename(library: PhotoLibrary) { _ui.value = _ui.value.copy(renameTarget = library) }
    fun cancelRename() { _ui.value = _ui.value.copy(renameTarget = null) }
    fun confirmRename(name: String) {
        val target = _ui.value.renameTarget ?: return
        _ui.value = _ui.value.copy(renameTarget = null)
        if (name.isBlank()) return
        viewModelScope.launch { photoRepository.renameLibrary(target.id, name.trim()) }
    }

    fun clearThumbnailCache() = viewModelScope.launch {
        val dir = File(context.filesDir, "photo_thumbs")
        val removed = runCatching { dir.listFiles()?.count { it.delete() } ?: 0 }.getOrDefault(0)
        _ui.value = _ui.value.copy(scanMessage = "Cleared $removed cached thumbnail(s). Deep scan to regenerate.")
    }

    fun dismissMessage() { _ui.value = _ui.value.copy(scanMessage = null) }

    // ── Scanning ────────────────────────────────────────────────────────────────

    private suspend fun scan(libraries: List<PhotoLibrary>, deep: Boolean) {
        if (libraries.isEmpty()) {
            _ui.value = _ui.value.copy(scanMessage = "No libraries to scan.")
            return
        }
        _ui.value = _ui.value.copy(scanning = true, scanMessage = "Scanning…")
        var totalPhotos = 0
        var errors = 0
        for (library in libraries) {
            val taskId = "photo_scan_${library.id}"
            val existing = photoRepository.getPhotosForLibrary(library.id)
            notifier.running(taskId, "Scanning ${library.displayName}", null)
            photoScanner.scan(library, deep, existing).collect { result ->
                when (result) {
                    is PhotoScanResult.Progress -> {
                        notifier.running(taskId, "Scanning ${result.libraryName}", null)
                        _ui.value = _ui.value.copy(scanMessage = "${result.libraryName}: ${result.photosFound} photos")
                    }
                    is PhotoScanResult.Complete -> {
                        photoRepository.replacePhotosForLibrary(result.libraryId, result.photos, System.currentTimeMillis())
                        totalPhotos += result.photos.size
                        notifier.complete(taskId, "Scanned ${library.displayName}", "${result.photos.size} photos")
                    }
                    is PhotoScanResult.Error -> {
                        errors++
                        notifier.failed(taskId, "Scan failed: ${library.displayName}", result.message)
                        _ui.value = _ui.value.copy(scanMessage = result.message)
                    }
                }
            }
        }
        _ui.value = _ui.value.copy(
            scanning = false,
            scanMessage = if (errors > 0) "Done with $errors error(s). Found $totalPhotos photos."
                          else "Found $totalPhotos photos.",
        )
    }

    private fun persistReadPermission(uri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    }.getOrElse { e -> Timber.w(e, "Failed to persist read permission for $uri"); false }
}
