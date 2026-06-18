package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.dao.LibrarySourceDao
import com.playfieldportal.core.data.database.dao.UnmatchedRomDao
import com.playfieldportal.core.data.database.entity.LibrarySourceEntity
import com.playfieldportal.core.data.database.entity.UnmatchedRomEntity
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.library.scanner.RomScanner
import com.playfieldportal.feature.library.scanner.ScanProgress
import com.playfieldportal.feature.library.scanner.ScanResult
import com.playfieldportal.feature.library.scanner.ScanType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

private val KEY_LIBRARY_ROOT   = stringPreferencesKey("library_root_path")
private val KEY_SETUP_COMPLETE = booleanPreferencesKey("library_setup_complete")

data class LibrarySource(
    val id: Long,
    val path: String,
    val label: String,
    val gameCount: Int,
    val platformId: String? = null,
    val isEnabled: Boolean = true,
)

data class LibrarySettingsUiState(
    val rootPath: String? = null,
    val setupComplete: Boolean = false,
    val sources: List<LibrarySource> = emptyList(),
    val isScanning: Boolean = false,
    val scanProgress: ScanProgress? = null,
    val scanMessage: String? = null,
    val lastScanTime: String? = null,
    val showUnmatched: Boolean = true,
)

@HiltViewModel
class LibrarySettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val librarySourceDao: LibrarySourceDao,
    private val gameDao: GameDao,
    private val gameRepository: GameRepository,
    private val unmatchedRomDao: UnmatchedRomDao,
    private val romScanner: RomScanner,
) : ViewModel() {

    private val _extra = MutableStateFlow(LibrarySettingsUiState())

    val uiState: StateFlow<LibrarySettingsUiState> = combine(
        librarySourceDao.observeAll(),
        _extra,
    ) { entities, extra ->
        extra.copy(
            sources = entities.map { e ->
                LibrarySource(
                    id         = e.id,
                    path       = e.path,
                    label      = e.label,
                    gameCount  = e.gameCount,
                    platformId = e.platformId,
                    isEnabled  = e.isEnabled,
                )
            },
            lastScanTime = entities.maxOfOrNull { it.lastScannedAt ?: 0L }
                ?.takeIf { it > 0L }
                ?.let { SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault()).format(Date(it)) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibrarySettingsUiState())

    init {
        viewModelScope.launch {
            context.pfpDataStore.data.collect { prefs ->
                _extra.update {
                    it.copy(
                        rootPath      = prefs[KEY_LIBRARY_ROOT],
                        setupComplete = prefs[KEY_SETUP_COMPLETE] ?: false,
                    )
                }
            }
        }
    }

    // ── Root directory setup ──────────────────────────────────────────────

    fun setupRoot(uri: Uri) {
        val path = uri.toRealPath() ?: run {
            _extra.update { it.copy(scanMessage = "Could not read that folder. Try a different location.") }
            return
        }
        viewModelScope.launch {
            persistReadPermission(uri)
            File(path).mkdirs()
            context.pfpDataStore.edit {
                it[KEY_LIBRARY_ROOT]   = path
                it[KEY_SETUP_COMPLETE] = true
            }
            librarySourceDao.insert(
                LibrarySourceEntity(path = path, label = "ROM Library")
            )
            Timber.i("ROM library root configured: $path")
        }
    }

    // ── Extra folder management ───────────────────────────────────────────

    // platformId = null means scan with extension detection; non-null locks all ROMs to that platform
    fun addExtraFolder(uri: Uri, platformId: String? = null) {
        val path = uri.toRealPath() ?: run {
            _extra.update { it.copy(scanMessage = "Could not read that folder path") }
            return
        }
        viewModelScope.launch {
            persistReadPermission(uri)
            File(path).mkdirs()
            val label = platformId?.uppercase() ?: path.substringAfterLast('/')
            librarySourceDao.insert(
                LibrarySourceEntity(path = path, label = label, platformId = platformId)
            )
            Timber.i("Extra ROM folder added: $path (platform=$platformId)")
        }
    }

    fun removeSource(id: Long) {
        viewModelScope.launch {
            librarySourceDao.deleteById(id)
        }
    }

    fun toggleSource(id: Long, enabled: Boolean) {
        viewModelScope.launch { librarySourceDao.setEnabled(id, enabled) }
    }

    // ── Scanning ──────────────────────────────────────────────────────────

    fun scanNow() {
        if (_extra.value.isScanning) return
        viewModelScope.launch {
            _extra.update { it.copy(isScanning = true, scanMessage = null, scanProgress = null) }

            val enabledSources = librarySourceDao.getEnabled()
            val folders = enabledSources.map { it.path }

            if (folders.isEmpty()) {
                _extra.update { it.copy(isScanning = false, scanMessage = "No scan folders configured") }
                return@launch
            }

            val existingPaths = gameDao.getAll().mapNotNull { it.romPath }.toSet()

            // Build a map of folder-path → platform override for platform-specific sources
            val platformOverrides = enabledSources
                .filter { it.platformId != null }
                .associate { it.path to it.platformId!! }

            var newGameCount = 0

            romScanner.scan(folders, ScanType.NEW_FILES_ONLY, existingPaths)
                .collect { result ->
                    when (result) {
                        is ScanResult.Progress -> {
                            _extra.update { it.copy(scanProgress = result.progress) }
                        }

                        is ScanResult.Complete -> {
                            newGameCount = result.newGames.size

                            result.newGames.forEach { game ->
                                val overridePlatform = platformOverrides.entries
                                    .firstOrNull { (folder, _) ->
                                        game.romPath?.startsWith(folder) == true
                                    }?.value
                                gameRepository.upsert(
                                    if (overridePlatform != null) game.copy(platformId = overridePlatform)
                                    else game
                                )
                            }

                            val unmatchedEntities = (result.unmatched + result.requiresUserAssignment)
                                .map { rom ->
                                    UnmatchedRomEntity(
                                        filePath           = rom.filePath,
                                        fileName           = rom.fileName,
                                        detectedPlatformId = rom.detectedPlatformId,
                                        foundAt            = System.currentTimeMillis(),
                                        resolvedPlatformId = null,
                                    )
                                }
                            if (unmatchedEntities.isNotEmpty()) {
                                unmatchedRomDao.insertAll(unmatchedEntities)
                            }

                            val now = System.currentTimeMillis()
                            enabledSources.forEach { source ->
                                librarySourceDao.updateScanResult(source.id, now, source.gameCount)
                            }

                            val already = result.alreadyInLibrary
                            val unmatched = result.unmatched.size + result.requiresUserAssignment.size
                            _extra.update {
                                it.copy(
                                    isScanning   = false,
                                    scanProgress = null,
                                    scanMessage  = buildString {
                                        append("$newGameCount new game${if (newGameCount != 1) "s" else ""}")
                                        if (already > 0)   append("  ·  $already already in library")
                                        if (unmatched > 0) append("  ·  $unmatched unmatched")
                                    },
                                )
                            }
                        }

                        is ScanResult.Error -> {
                            _extra.update {
                                it.copy(isScanning = false, scanProgress = null, scanMessage = "Scan error: ${result.message}")
                            }
                        }
                    }
                }
        }
    }

    // ── Advanced ──────────────────────────────────────────────────────────

    fun setShowUnmatched(show: Boolean) = _extra.update { it.copy(showUnmatched = show) }

    fun clearLibrary() {
        viewModelScope.launch {
            gameDao.deleteAll()
            Timber.i("Library cleared")
        }
    }

    fun dismissMessage() = _extra.update { it.copy(scanMessage = null) }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun Uri.toRealPath(): String? = try {
        val docId = DocumentsContract.getTreeDocumentId(this)
        val parts = docId.split(":")
        when {
            parts.size < 2        -> null
            parts[0] == "primary" -> "/storage/emulated/0/${parts[1]}"
            else                  -> "/storage/${parts[0]}/${parts[1]}"  // removable SD card
        }
    } catch (e: Exception) {
        Timber.w(e, "Could not extract real path from $this")
        null
    }
}
