package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import com.playfieldportal.feature.library.scanner.PlatformFolderHintResolver
import com.playfieldportal.feature.library.scanner.RomScanner
import com.playfieldportal.feature.library.scanner.ScanResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
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
    // Set when the screen should launch the folder picker to set up the ES-DE ROM structure.
    val awaitingRomRootSetup: Boolean = false,
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
    private val romRootRepository: RomRootRepository,
    private val folderHintResolver: PlatformFolderHintResolver,
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

            // A card's ROMs come from (in priority): its own SAF grant; else every ROM root's
            // subfolder that maps to this platform (aggregated, so an SD card adds to the same
            // console); else its legacy raw directory.
            val sources = buildScanSources(card)
            if (sources.isEmpty()) {
                _scratch.update { it.copy(message = "${card.displayName}: ROM folder not configured.") }
                return@launch
            }
            _scratch.update { it.copy(scanningPlatformIds = it.scanningPlatformIds + platformId) }

            // Live, growing set so a ROM already added from one root isn't re-added from another.
            val existing = runCatching {
                gameRepository.observeByPlatform(platformId).first().mapNotNull { it.romPath }.toMutableSet()
            }.getOrDefault(mutableSetOf())

            var added = 0
            for (source in sources) {
                source(existing).collect { result ->
                    when (result) {
                        is ScanResult.Complete -> {
                            result.newGames.forEach {
                                gameRepository.upsert(it)
                                it.romPath?.let(existing::add)
                            }
                            added += result.newGames.size
                        }
                        is ScanResult.Error -> _scratch.update { it.copy(message = "${card.displayName}: ${result.message}") }
                        else -> Unit
                    }
                }
            }
            if (added > 0) memoryCardRepository.recordScan(platformId, System.currentTimeMillis())

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

    // ── ES-DE folder setup (create the directory structure) ──────────────────────
    //
    // Lets the user point at any (empty) folder and have PFP create the full ES-DE system-folder
    // set inside it (gba/, snes/, psx/, …). The picked folder also becomes the ROM Root, so after
    // copying games in the user just taps Auto-Detect. Requires a write grant on the folder.
    fun requestRomFolderSetup() {
        _scratch.update { it.copy(awaitingRomRootSetup = true) }
    }

    fun onRomFolderSetupPicked(uri: Uri?) {
        _scratch.update { it.copy(awaitingRomRootSetup = false) }
        if (uri == null) return
        viewModelScope.launch {
            // Read+write: we must create folders now and read them when scanning later.
            romRootRepository.persist(uri, writable = true)
            romRootRepository.add(uri.toString())

            // One ES-DE folder per supported platform (skip the app-based Android library).
            val names = memoryCardRepository.availablePlatformCatalog()
                .map { folderHintResolver.esDeFolderName(it.id) }
                .filter { it.isNotBlank() && it != "android" }
                .distinct()

            val result = romScanner.createSubfolders(uri.toString(), names)
            Timber.i("ES-DE setup — root=$uri created=${result.created} existing=${result.existing}")
            _scratch.update {
                it.copy(
                    message = "ROM Root ready: ${result.created} folder(s) created" +
                        (if (result.existing > 0) ", ${result.existing} already there" else "") +
                        ". Copy your games into the matching folders, then Auto-Detect.",
                )
            }
        }
    }

    // ── Single-scan autoload from the ES-DE ROM root ─────────────────────────────
    //
    // Walks the granted ROM root's top-level subfolders, maps each to a platform by its ES-DE
    // folder name (PlatformFolderHintResolver), auto-creates a Memory Card for any system that
    // doesn't have one yet (pointed at its subfolder under the root), then scans every detected
    // console — the whole library set up from one action. Folders that don't map to a supported
    // platform are skipped.
    fun scanRomRoot() {
        viewModelScope.launch {
            val roots = romRootRepository.getAll()
            if (roots.isEmpty()) {
                _scratch.update { it.copy(message = "Add a ROM Root first in Settings → Folder Access.") }
                return@launch
            }

            val catalog  = memoryCardRepository.availablePlatformCatalog().associateBy { it.id }
            val haveCard = memoryCardRepository.getAll().map { it.platformId }.toMutableSet()

            var scannedFolders = 0
            val platformsWithGames = mutableSetOf<String>()
            var newCards = 0
            var totalAdded = 0

            // Scan every root's subfolders. A folder only becomes a console if it actually contains
            // ROMs — empty ES-DE folders (e.g. the ones "Set Up ROM Folders" created) are skipped.
            for (rootUri in roots) {
                val rootRaw = RomRootRepository.rawPathOfTree(rootUri)
                for (name in romScanner.listSubfolderNames(rootUri)) {
                    scannedFolders++
                    val platformId = folderHintResolver.detectFromFolderName(name) ?: continue
                    val platform   = catalog[platformId] ?: continue
                    val childDocId = RomRootRepository.childDocIdOf(rootUri, name) ?: continue

                    val exts = memoryCardRepository.getById(platformId)?.supportedExtensions
                        ?.takeIf { it.isNotEmpty() } ?: platform.romExtensions
                    if (exts.isEmpty()) continue   // nothing scannable for this platform

                    val existing = runCatching {
                        gameRepository.observeByPlatform(platformId).first().mapNotNull { it.romPath }.toSet()
                    }.getOrDefault(emptySet())

                    val found = firstComplete(
                        romScanner.scanTree(rootUri, exts, platformId, true, existing, startDocId = childDocId)
                    )?.newGames.orEmpty()

                    if (found.isEmpty()) continue   // empty (or fully-known) folder → no card, no change

                    if (platformId !in haveCard) {
                        memoryCardRepository.addCard(
                            platformId   = platformId,
                            displayName  = "${platform.name} Memory Card",
                            romDirectory = rootRaw?.let { "${it.trimEnd('/')}/$name" },
                            emulatorId   = null,
                        )
                        haveCard.add(platformId)
                        newCards++
                    }
                    found.forEach { gameRepository.upsert(it) }
                    memoryCardRepository.recordScan(platformId, System.currentTimeMillis())
                    platformsWithGames.add(platformId)
                    totalAdded += found.size
                }
            }

            val rootLabel = "${roots.size} root${if (roots.size == 1) "" else "s"}"
            val message = if (platformsWithGames.isEmpty()) {
                "Scanned $scannedFolders folder(s) across $rootLabel; no new ROMs found. " +
                    "Copy games into the matching system folders and try again."
            } else {
                "Loaded ${platformsWithGames.size} system(s)" +
                    (if (newCards > 0) " ($newCards new console(s))" else "") +
                    ", $totalAdded ROM(s) from $rootLabel."
            }
            _scratch.update { it.copy(message = message) }
            Timber.i("ROM root autoload — folders=$scannedFolders systems=${platformsWithGames.size} new=$newCards roms=$totalAdded roots=${roots.size}")
        }
    }

    // ── Scan-source resolution shared by scanConsole / autoload ──────────────────

    // Lazy scan-flow factories for a card, evaluated with the live "already-known paths" set so
    // multi-root aggregation de-dupes across sources.
    private suspend fun buildScanSources(
        card: com.playfieldportal.core.domain.model.MemoryCard,
    ): List<(Set<String>) -> Flow<ScanResult>> {
        val exts = card.supportedExtensions
        val rec  = card.scanRecursively
        // 1. Own explicit SAF folder.
        if (!card.treeUri.isNullOrBlank()) {
            return listOf({ existing -> romScanner.scanTree(card.treeUri!!, exts, card.platformId, rec, existing) })
        }
        // 2. Root-managed: every ROM root subfolder that maps to this platform (internal + SD).
        val targets = rootScanTargets(card.platformId)
        if (targets.isNotEmpty()) {
            return targets.map { (rootUri, childDocId) ->
                { existing: Set<String> ->
                    romScanner.scanTree(rootUri, exts, card.platformId, rec, existing, startDocId = childDocId)
                }
            }
        }
        // 3. Legacy raw path.
        if (!card.romDirectory.isNullOrBlank()) {
            return listOf({ existing -> romScanner.scanDirectory(card.romDirectory!!, exts, card.platformId, rec, existing) })
        }
        return emptyList()
    }

    // (rootUri, childDocId) for every ROM root that has a subfolder mapping to [platformId]. Uses
    // the real (case-correct) folder name from the provider, so it works regardless of casing.
    private suspend fun rootScanTargets(platformId: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (rootUri in romRootRepository.getAll()) {
            for (name in romScanner.listSubfolderNames(rootUri)) {
                if (folderHintResolver.detectFromFolderName(name) == platformId) {
                    RomRootRepository.childDocIdOf(rootUri, name)?.let { out.add(rootUri to it) }
                }
            }
        }
        return out
    }

    private suspend fun firstComplete(flow: Flow<ScanResult>): ScanResult.Complete? {
        var complete: ScanResult.Complete? = null
        flow.collect { if (it is ScanResult.Complete) complete = it }
        return complete
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
