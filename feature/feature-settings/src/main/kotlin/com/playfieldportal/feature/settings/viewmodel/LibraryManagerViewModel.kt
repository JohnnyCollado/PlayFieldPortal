package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import com.playfieldportal.feature.library.scanner.RomScanner
import com.playfieldportal.feature.library.scanner.ScanResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// ── Screen model ────────────────────────────────────────────────────────────────

// Focus key for the "Add Console" row so focus returns to it after the add flow.
const val ADD_CONSOLE_FOCUS_KEY = "add_console"

// Platform whose library is built from installed apps (picker) rather than a ROM folder.
private const val ANDROID_PLATFORM_ID = "android"

enum class LibraryStep { LIST, PICK_PLATFORM, PICK_EMULATOR, SCAN_PROMPT, CARD_DETAIL }

enum class DirectoryPickPurpose { ADD, CHANGE }

data class LibraryCardRow(
    val platformId: String,
    val displayName: String,
    val enabled: Boolean,
    val pinned: Boolean,
    val treeUri: String?,
    val romDirectory: String?,
    val emulatorName: String?,
    val extensions: List<String>,
    val gameCount: Int,
)

data class PlatformOption(val id: String, val name: String, val shortName: String)
data class LibraryAppRow(val gameId: Long, val label: String)

data class EmulatorOption(val id: String?, val name: String)

data class LibraryManagerUiState(
    val step: LibraryStep = LibraryStep.LIST,
    val cards: List<LibraryCardRow> = emptyList(),

    // Add Console flow scratch
    val platformOptions: List<PlatformOption> = emptyList(),
    val emulatorOptions: List<EmulatorOption> = emptyList(),
    val pendingPlatformId: String? = null,
    val pendingPlatformName: String? = null,
    val pendingDirectory: String? = null,
    // Persisted SAF tree URI for the folder being added (source of truth for scan/launch).
    val pendingTreeUri: String? = null,
    val pendingEmulatorId: String? = null,

    // Card detail (edit) target
    val detailPlatformId: String? = null,
    // Apps in the Android library (managed from its detail screen, not scanned).
    val androidApps: List<LibraryAppRow> = emptyList(),

    // UI signals
    val awaitingDirectoryPick: DirectoryPickPurpose? = null,
    val renameTargetPlatformId: String? = null,
    val scanningPlatformIds: Set<String> = emptySet(),
    val message: String? = null,
    // Row to restore focus to when returning to the LIST from a child screen.
    val returnFocusKey: String? = null,
) {
    val detailCard: LibraryCardRow? get() = cards.firstOrNull { it.platformId == detailPlatformId }
}

@HiltViewModel
class LibraryManagerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryCardRepository: MemoryCardRepository,
    private val romScanner: RomScanner,
    private val gameRepository: GameRepository,
    private val emulatorProfileRepository: EmulatorProfileRepository,
) : ViewModel() {

    private val _scratch = MutableStateFlow(LibraryManagerUiState())

    val uiState: StateFlow<LibraryManagerUiState> = combine(
        memoryCardRepository.observeAll(),
        gameRepository.observeAll(),
        emulatorProfileRepository.profiles,
        _scratch,
    ) { cards, games, profiles, scratch ->
        val emulatorNames = profiles.associate { it.id to it.name }
        val counts = games.groupBy { it.platformId }.mapValues { it.value.size }
        scratch.copy(
            cards = cards.map { card ->
                LibraryCardRow(
                    platformId   = card.platformId,
                    displayName  = card.displayName,
                    enabled      = card.enabled,
                    pinned       = card.pinned,
                    treeUri      = card.treeUri,
                    romDirectory = card.romDirectory,
                    emulatorName = card.emulatorId?.let { emulatorNames[it] },
                    extensions   = card.supportedExtensions,
                    gameCount    = counts[card.platformId] ?: card.gameCount,
                )
            },
            androidApps = games.filter { it.platformId == ANDROID_PLATFORM_ID }
                .map { LibraryAppRow(it.id, it.displayTitle) }
                .sortedBy { it.label.lowercase() },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryManagerUiState())

    /** Remove an app from the Android library (deletes its entry, like removing a game). */
    fun removeApp(gameId: Long) {
        viewModelScope.launch {
            gameRepository.delete(gameId)
            memoryCardRepository.recountGames(ANDROID_PLATFORM_ID)
        }
    }

    // ── Navigation ──────────────────────────────────────────────────────────────

    // Returns true if the back press was consumed internally (sub-screen → list).
    fun onBack(): Boolean {
        val step = _scratch.value.step
        if (step == LibraryStep.LIST) return false
        resetToList()
        return true
    }

    private fun resetToList() {
        _scratch.update {
            LibraryManagerUiState(
                step                = LibraryStep.LIST,
                message             = it.message,
                scanningPlatformIds = it.scanningPlatformIds,
                returnFocusKey      = it.returnFocusKey,   // restore focus to the row we came from
            )
        }
    }

    fun dismissMessage() = _scratch.update { it.copy(message = null) }

    // ── Add Console flow ──────────────────────────────────────────────────────────

    fun startAddConsole() {
        viewModelScope.launch {
            val options = memoryCardRepository.unconfiguredPlatforms()
                .map { PlatformOption(it.id, it.name, it.shortName) }
            if (options.isEmpty()) {
                _scratch.update { it.copy(message = "Every supported platform already has a Memory Card.") }
                return@launch
            }
            _scratch.update {
                it.copy(
                    step               = LibraryStep.PICK_PLATFORM,
                    platformOptions    = options,
                    pendingPlatformId  = null,
                    pendingPlatformName = null,
                    pendingDirectory   = null,
                    pendingEmulatorId  = null,
                    returnFocusKey     = ADD_CONSOLE_FOCUS_KEY,
                )
            }
        }
    }

    fun onPlatformChosen(option: PlatformOption) {
        // Android libraries are built from installed apps, not a scanned ROM folder. Create the
        // card straight away (no directory / emulator) — the user then adds apps from the
        // Android card in Games via "Find Games" (the installed-app picker).
        if (option.id == ANDROID_PLATFORM_ID) {
            viewModelScope.launch {
                memoryCardRepository.addCard(
                    platformId   = option.id,
                    displayName  = defaultDisplayName(option.name),
                    romDirectory = null,
                    emulatorId   = null,
                )
                resetToList()
                _scratch.update {
                    it.copy(message = "Android library added. Open it in Games → Find Games to add apps.")
                }
            }
            return
        }
        _scratch.update {
            it.copy(
                pendingPlatformId   = option.id,
                pendingPlatformName = option.name,
                awaitingDirectoryPick = DirectoryPickPurpose.ADD,
            )
        }
    }

    fun onEmulatorChosen(option: EmulatorOption) {
        _scratch.update { it.copy(pendingEmulatorId = option.id, step = LibraryStep.SCAN_PROMPT) }
    }

    fun confirmAddConsole(scanNow: Boolean) {
        val s = _scratch.value
        val platformId = s.pendingPlatformId ?: return
        viewModelScope.launch {
            val displayName = defaultDisplayName(s.pendingPlatformName ?: platformId)
            memoryCardRepository.addCard(
                platformId   = platformId,
                displayName  = displayName,
                romDirectory = s.pendingDirectory,
                emulatorId   = s.pendingEmulatorId,
            )
            // Attach the SAF grant so the card scans/launches via content URIs (no permission).
            s.pendingTreeUri?.let { memoryCardRepository.setSafFolder(platformId, it, s.pendingDirectory) }
            resetToList()
            if (scanNow && (s.pendingTreeUri != null || s.pendingDirectory != null)) scanConsole(platformId)
        }
    }

    private fun defaultDisplayName(platformName: String): String = "$platformName Memory Card"

    // ── Directory picking (SAF) ───────────────────────────────────────────────────

    fun onDirectoryPicked(uri: Uri) {
        val purpose = _scratch.value.awaitingDirectoryPick
        // Keep the SAF grant: the tree URI is the scan/launch source of truth (no storage
        // permission needed). The derived raw path is display + a {rom_path} fallback only.
        persistReadPermission(uri)
        val treeUri = uri.toString()
        val path = uri.toRealPath()

        when (purpose) {
            DirectoryPickPurpose.ADD -> viewModelScope.launch {
                val platformId = _scratch.value.pendingPlatformId
                val options = buildEmulatorOptions(platformId)
                _scratch.update {
                    it.copy(
                        pendingDirectory      = path,
                        pendingTreeUri        = treeUri,
                        emulatorOptions       = options,
                        awaitingDirectoryPick = null,
                        step                  = LibraryStep.PICK_EMULATOR,
                    )
                }
            }
            DirectoryPickPurpose.CHANGE -> viewModelScope.launch {
                val platformId = _scratch.value.detailPlatformId ?: return@launch
                memoryCardRepository.setSafFolder(platformId, treeUri, path)
                _scratch.update { it.copy(awaitingDirectoryPick = null) }
            }
            null -> _scratch.update { it.copy(awaitingDirectoryPick = null) }
        }
    }

    // User cancelled the system folder picker.
    fun onDirectoryPickCancelled() {
        val purpose = _scratch.value.awaitingDirectoryPick
        _scratch.update {
            it.copy(
                awaitingDirectoryPick = null,
                // If they cancelled while adding, drop back to platform pick.
                step = if (purpose == DirectoryPickPurpose.ADD) LibraryStep.PICK_PLATFORM else it.step,
            )
        }
    }

    private suspend fun buildEmulatorOptions(platformId: String?): List<EmulatorOption> {
        val installed = platformId?.let { emulatorProfileRepository.getProfilesForPlatform(it) } ?: emptyList()
        return buildList {
            installed.forEach { add(EmulatorOption(it.id, it.name)) }
            add(EmulatorOption(null, "Decide later"))
        }
    }

    // ── Card detail (edit) ────────────────────────────────────────────────────────

    fun openCardDetail(platformId: String) {
        _scratch.update {
            it.copy(step = LibraryStep.CARD_DETAIL, detailPlatformId = platformId, returnFocusKey = platformId)
        }
    }

    fun toggleEnabled(platformId: String, enabled: Boolean) {
        viewModelScope.launch { memoryCardRepository.setEnabled(platformId, enabled) }
    }

    fun togglePinned(platformId: String, pinned: Boolean) {
        viewModelScope.launch { memoryCardRepository.setPinned(platformId, pinned) }
    }

    fun moveCard(platformId: String, up: Boolean) {
        viewModelScope.launch { memoryCardRepository.move(platformId, up) }
    }

    fun removeCard(platformId: String) {
        viewModelScope.launch {
            memoryCardRepository.remove(platformId)
            if (_scratch.value.detailPlatformId == platformId) resetToList()
        }
    }

    fun requestChangeDirectory(platformId: String) {
        _scratch.update {
            it.copy(detailPlatformId = platformId, awaitingDirectoryPick = DirectoryPickPurpose.CHANGE)
        }
    }

    fun beginRename(platformId: String) = _scratch.update { it.copy(renameTargetPlatformId = platformId) }
    fun cancelRename() = _scratch.update { it.copy(renameTargetPlatformId = null) }

    fun confirmRename(newName: String) {
        val platformId = _scratch.value.renameTargetPlatformId ?: return
        val trimmed = newName.trim()
        viewModelScope.launch {
            if (trimmed.isNotEmpty()) memoryCardRepository.rename(platformId, trimmed)
            _scratch.update { it.copy(renameTargetPlatformId = null) }
        }
    }

    fun setEmulatorForDetail(option: EmulatorOption) {
        val platformId = _scratch.value.detailPlatformId ?: return
        viewModelScope.launch { memoryCardRepository.setEmulator(platformId, option.id) }
    }

    // ── Supported scan extensions ───────────────────────────────────────────────

    fun addExtension(platformId: String, ext: String) {
        val clean = ext.trim().lowercase().removePrefix(".").filter { it.isLetterOrDigit() }
        if (clean.isBlank()) return
        viewModelScope.launch {
            val card = memoryCardRepository.getById(platformId) ?: return@launch
            if (clean in card.supportedExtensions) return@launch
            memoryCardRepository.setExtensions(platformId, card.supportedExtensions + clean)
        }
    }

    fun removeExtension(platformId: String, ext: String) {
        viewModelScope.launch {
            val card = memoryCardRepository.getById(platformId) ?: return@launch
            memoryCardRepository.setExtensions(platformId, card.supportedExtensions - ext)
        }
    }

    fun loadEmulatorOptionsForDetail() {
        viewModelScope.launch {
            val platformId = _scratch.value.detailPlatformId
            _scratch.update { it.copy(emulatorOptions = buildEmulatorOptions(platformId)) }
        }
    }

    // ── Scanning ────────────────────────────────────────────────────────────────

    fun scanConsole(platformId: String) {
        if (platformId in _scratch.value.scanningPlatformIds) return
        viewModelScope.launch {
            val card = memoryCardRepository.getById(platformId) ?: return@launch
            // Prefer the SAF tree URI (no permission needed); fall back to the legacy raw directory.
            val hasSaf = !card.treeUri.isNullOrBlank()
            if (!hasSaf && card.romDirectory.isNullOrBlank()) {
                _scratch.update { it.copy(message = "${card.displayName}: ROM directory not configured.") }
                return@launch
            }
            _scratch.update { it.copy(scanningPlatformIds = it.scanningPlatformIds + platformId) }

            val existing = runCatching {
                gameRepository.observeByPlatform(platformId).first().mapNotNull { it.romPath }.toSet()
            }.getOrDefault(emptySet())

            var added = 0
            val flow = if (hasSaf) {
                romScanner.scanTree(card.treeUri!!, card.supportedExtensions, platformId, card.scanRecursively, existing)
            } else {
                romScanner.scanDirectory(card.romDirectory!!, card.supportedExtensions, platformId, card.scanRecursively, existing)
            }
            flow
                .collect { result ->
                    when (result) {
                        is ScanResult.Complete -> {
                            result.newGames.forEach { gameRepository.upsert(it) }
                            memoryCardRepository.recordScan(platformId, System.currentTimeMillis())
                            added = result.newGames.size
                        }
                        is ScanResult.Error -> _scratch.update { it.copy(message = "${card.displayName}: ${result.message}") }
                        else -> Unit
                    }
                }

            _scratch.update {
                it.copy(
                    scanningPlatformIds = it.scanningPlatformIds - platformId,
                    message = "${card.displayName}: ${if (added == 0) "no new ROMs" else "$added new ROM(s) added"}",
                )
            }
            Timber.i("Library Manager scan complete for $platformId: $added new")
        }
    }

    fun scanAllConsoles() {
        viewModelScope.launch {
            memoryCardRepository.getAll()
                .filter { it.enabled && (!it.treeUri.isNullOrBlank() || !it.romDirectory.isNullOrBlank()) }
                .forEach { scanConsole(it.platformId) }
        }
    }

    // ── SAF helpers ────────────────────────────────────────────────────────────────

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun Uri.toRealPath(): String? = try {
        val docId = DocumentsContract.getTreeDocumentId(this)
        val parts = docId.split(":")
        when {
            parts.size < 2        -> null
            parts[0] == "primary" -> "/storage/emulated/0/${parts[1]}"
            else                  -> "/storage/${parts[0]}/${parts[1]}"   // removable SD card
        }
    } catch (e: Exception) {
        Timber.w(e, "Could not extract real path from $this")
        null
    }
}
