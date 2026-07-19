package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.repository.FolderLinkStatus
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.core.data.repository.SafGrants
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.feature.appbar.LauncherShortcutRepository
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import com.playfieldportal.feature.launcher.PcLauncherAdapters
import com.playfieldportal.feature.launcher.PcLauncherCatalog
import com.playfieldportal.feature.launcher.PcLauncherType
import com.playfieldportal.core.data.platform.PlatformFolderHintResolver
import com.playfieldportal.feature.library.scanner.RomScanner
import com.playfieldportal.feature.library.scanner.ScanResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
private const val WINDOWS_PLATFORM_ID = "windows"

private const val INVALID_GAME_ID_MESSAGE =
    "Enter a valid game ID — a number, or a local_… ID copied from the game's page in the launcher."

enum class LibraryStep { LIST, PICK_PLATFORM, PICK_EMULATOR, SCAN_PROMPT, CARD_DETAIL, IMPORT_PC }

// Focus key for the "Import PC Games" row so focus returns to it from the import section.
const val IMPORT_PC_FOCUS_KEY = "import_pc_games"

// One supported PC launcher with its install state, for the Import PC Games section.
// packageName is the actually-installed package (a launcher may have several); canAddById is true
// when PFP knows this launcher's launch-intent contract.
data class PcLauncherRow(
    val type: PcLauncherType,
    val name: String,
    val installed: Boolean,
    val packageName: String?,
    val canAddById: Boolean,
)

// One PC game already captured from a launcher (via pin/INSTALL_SHORTCUT), importable into the
// Windows Games card. gameId references the existing games row.
data class PcGameRow(val gameId: Long, val title: String, val launcherName: String)


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
    val pendingEmulatorId: String? = null,

    // Card detail (edit) target
    val detailPlatformId: String? = null,
    // Apps in the Android library (managed from its detail screen, not scanned).
    val androidApps: List<LibraryAppRow> = emptyList(),

    // ROM Root Access: managed root folders (one SAF grant each; consoles scan subfolders).
    val romRoots: List<RootFolderRow> = emptyList(),

    // Import PC Games section
    val pcLaunchers: List<PcLauncherRow> = emptyList(),
    val pcGames: List<PcGameRow> = emptyList(),
    // True when PFP is the active Home app (unlocks auto-import of published game shortcuts).
    val isHomeLauncher: Boolean = false,

    // UI signals
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
    private val launcherShortcutRepository: LauncherShortcutRepository,
    private val scanTombstoneDao: com.playfieldportal.core.data.database.dao.ScanTombstoneDao,
    private val emuGameImporter: com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamGameImporter,
    private val windowsLibrarySetup: com.playfieldportal.core.data.repository.WindowsLibrarySetup,
    private val pcShortcutImporter: com.playfieldportal.feature.launcher.PcShortcutImporter,
    private val pcGameScanner: com.playfieldportal.feature.settings.pc.PcGameScanner,
    private val localSteamSchemaGenerator: com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamSchemaGenerator,
) : ViewModel() {

    private val _scratch = MutableStateFlow(LibraryManagerUiState())

    // Drives the per-game "generate the missing achievement schema?" prompt after a PC scan; the
    // same controller and dialog serve the XMB Windows card (see XMBViewModel).
    private val schemaPrompts =
        com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamSchemaPromptController(
            localSteamSchemaGenerator, viewModelScope,
        )
    val schemaPrompt: StateFlow<com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamSchemaPromptController.Prompt?> =
        schemaPrompts.prompt

    fun onSchemaPromptNo() = schemaPrompts.no()
    fun onSchemaPromptYes() = schemaPrompts.yes()
    fun onSchemaPromptYesToAll() = schemaPrompts.yesToAll()

    init {
        // Reactive, not one-shot: roots granted anywhere (the first-run wizard, a restore) show
        // up here immediately — this ViewModel is activity-scoped and outlives any single open.
        // distinctUntilChanged matters: the backing DataStore is app-wide, so without it every
        // unrelated preference write would re-run the persisted-grant binder scan.
        viewModelScope.launch {
            romRootRepository.roots.distinctUntilChanged().collect { refreshRomRoots() }
        }
    }

    val uiState: StateFlow<LibraryManagerUiState> = combine(
        memoryCardRepository.observeAll(),
        gameRepository.observeAll(),
        emulatorProfileRepository.profiles,
        _scratch,
    ) { cards, games, profiles, scratch ->
        val emulatorNames = profiles.associate { it.id to it.name }
        val counts = games.groupBy { it.platformId }.mapValues { it.value.size }
        scratch.copy(
            // Each root shows the consoles homed under it (matched by the card's directory).
            romRoots = scratch.romRoots.map { root ->
                val rootRaw = RomRootRepository.rawPathOfTree(root.treeUri)?.trimEnd('/')
                val homed = if (rootRaw == null) emptyList() else cards.mapNotNull { card ->
                    card.romDirectory?.takeIf { it.startsWith("$rootRaw/") }
                        ?.let { card.displayName.removeSuffix(" Memory Card") }
                }
                root.copy(consoles = homed.takeIf { it.isNotEmpty() }?.joinToString(", "))
            },
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
            // PC games captured from a supported launcher (pin / INSTALL_SHORTCUT) — they carry a
            // launchable reference (shortcut id or stored intent) back into the source app.
            // Entries already living in the Windows Games card are done; only strays show here.
            pcGames = games.mapNotNull { g ->
                val launcher = PcLauncherCatalog.forPackage(g.packageName) ?: return@mapNotNull null
                if (g.shortcutId == null && g.launchIntentUri == null) return@mapNotNull null
                if (g.platformId == WINDOWS_PLATFORM_ID) return@mapNotNull null
                PcGameRow(g.id, g.displayTitle, launcher.displayName)
            }.sortedBy { it.title.lowercase() },
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
        // Import PC Games opened from the Windows card detail returns there, not to the list.
        if (step == LibraryStep.IMPORT_PC && _scratch.value.detailPlatformId != null) {
            _scratch.update { it.copy(step = LibraryStep.CARD_DETAIL) }
            return true
        }
        resetToList()
        return true
    }

    private fun resetToList() {
        // Copy-with-clear, never a fresh state: display fields (cards, romRoots, message, scan
        // progress — and whatever gets added next) survive by DEFAULT; only the transient
        // sub-screen scratch is reset. A fresh-state rebuild silently wipes any field someone
        // forgets to carry over (romRoots was the live instance of that bug).
        _scratch.update {
            it.copy(
                step                   = LibraryStep.LIST,
                platformOptions        = emptyList(),
                emulatorOptions        = emptyList(),
                pendingPlatformId      = null,
                pendingPlatformName    = null,
                pendingDirectory       = null,
                pendingEmulatorId      = null,
                detailPlatformId       = null,
                androidApps            = emptyList(),
                pcLaunchers            = emptyList(),
                pcGames                = emptyList(),
                renameTargetPlatformId = null,
                awaitingRomRootSetup   = false,
            )
        }
    }

    fun dismissMessage() = _scratch.update { it.copy(message = null) }

    // ── Add Console flow ──────────────────────────────────────────────────────────

    fun startAddConsole() {
        viewModelScope.launch {
            // Consoles scan their subfolder under the ROM Root — without a root there is
            // nothing to add a console against, so setting one up comes first.
            if (romRootRepository.getAll().isEmpty()) {
                _scratch.update {
                    it.copy(message = "Set up a ROM Root first — grant your ROM folder under ROM Root Access, then add consoles.")
                }
                return@launch
            }
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
        // Console folders come from the ROM Root — the subfolder already recognized as this
        // platform under a granted root, else the platform's ES-DE folder name under the first
        // root. No per-console folder picker: one root grant covers every console, so a user
        // without a root is pointed at ROM Root Access instead.
        viewModelScope.launch {
            val roots = romRootRepository.getAll()
            if (roots.isEmpty()) {
                _scratch.update {
                    it.copy(message = "Set up a ROM Root first — grant your ROM folder under ROM Root Access, then add consoles.")
                }
                return@launch
            }
            var chosenRoot = roots.first()
            var folderName = folderHintResolver.esDeFolderName(option.id)
            outer@ for (rootUri in roots) {
                for (name in romScanner.listSubfolderNames(rootUri)) {
                    if (folderHintResolver.detectFromFolderName(name) == option.id) {
                        chosenRoot = rootUri
                        folderName = name
                        break@outer
                    }
                }
            }
            val rootRaw = RomRootRepository.rawPathOfTree(chosenRoot)
            val directory = rootRaw?.let { "${it.trimEnd('/')}/$folderName" }
            // Windows games launch through PC launchers, not an emulator profile — skip the
            // emulator step entirely, straight to the scan prompt.
            if (option.id == WINDOWS_PLATFORM_ID) {
                _scratch.update {
                    it.copy(
                        pendingPlatformId   = option.id,
                        pendingPlatformName = option.name,
                        pendingDirectory    = directory,
                        pendingEmulatorId   = null,
                        step                = LibraryStep.SCAN_PROMPT,
                    )
                }
                return@launch
            }
            val options = buildEmulatorOptions(option.id)
            _scratch.update {
                it.copy(
                    pendingPlatformId   = option.id,
                    pendingPlatformName = option.name,
                    pendingDirectory    = directory,
                    emulatorOptions     = options,
                    step                = LibraryStep.PICK_EMULATOR,
                )
            }
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
            // No per-card SAF grant: root-managed consoles scan and launch through the ROM
            // Root's recursive grant (buildScanSources maps the card to its root subfolder).
            resetToList()
            if (scanNow) {
                // Windows uses the PC import scan (setup self-heal, launcher exports, emu
                // folders) — the generic ROM directory walk would find nothing to import.
                // Other consoles scan through the ROM roots (buildScanSources maps the card
                // to its subfolder under every granted root).
                if (platformId == WINDOWS_PLATFORM_ID) scanPcGamesFolder()
                else scanConsole(platformId)
            }
        }
    }

    private fun defaultDisplayName(platformName: String): String = "$platformName Memory Card"

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
        // Opening the Windows card self-heals its setup: assigns <ROM Root>/windows (creating it
        // and the import/ drop-folder when the grant permits) if no directory is set yet.
        if (platformId == WINDOWS_PLATFORM_ID) {
            viewModelScope.launch { runCatching { windowsLibrarySetup.ensure() } }
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

    /**
     * Scans one console's folders for new ROMs. With [removeMissing] the same directory walk
     * also deletes entries whose ROM file has vanished ([ScanResult.Complete.presentRomPaths]),
     * so removal costs no second pass. Removal is skipped when any source errors or reports no
     * survey (e.g. an unmounted SD card must not wipe that console's games).
     */
    fun scanConsole(platformId: String, removeMissing: Boolean = false) {
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

            val dbGames = runCatching {
                gameRepository.observeByPlatform(platformId).first()
            }.getOrDefault(emptyList())

            // Live, growing set so a ROM already added from one root isn't re-added from another.
            // Tombstoned paths (user-removed games) count as existing so scans skip them.
            val existing = runCatching {
                (dbGames.mapNotNull { it.romPath } +
                    scanTombstoneDao.getPathsForPlatform(platformId)).toMutableSet()
            }.getOrDefault(mutableSetOf())

            var added = 0
            var scanErrored = false
            // Union of on-disk ROM paths across all sources; null once any source can't survey.
            var present: MutableSet<String>? = mutableSetOf()
            for (source in sources) {
                source(existing).collect { result ->
                    when (result) {
                        is ScanResult.Complete -> {
                            result.newGames.forEach {
                                gameRepository.upsert(it)
                                it.romPath?.let(existing::add)
                            }
                            added += result.newGames.size
                            result.presentRomPaths?.let { paths -> present?.addAll(paths) }
                                ?: run { present = null }
                        }
                        is ScanResult.Error -> {
                            scanErrored = true
                            _scratch.update { it.copy(message = "${card.displayName}: ${result.message}") }
                        }
                        else -> Unit
                    }
                }
            }

            var removed = 0
            val survey = present
            if (removeMissing && !scanErrored && survey != null) {
                // Plain delete, no tombstone: the file is gone, and if it ever comes back a
                // future scan should resurrect it.
                val gone = dbGames.filter { it.romPath != null && it.romPath !in survey }
                gone.forEach { gameRepository.delete(it.id) }
                removed = gone.size
            }

            if (added > 0 || removed > 0) {
                memoryCardRepository.recordScan(platformId, System.currentTimeMillis())
                memoryCardRepository.recountGames(platformId)
            }

            _scratch.update {
                it.copy(
                    scanningPlatformIds = it.scanningPlatformIds - platformId,
                    message = "${card.displayName}: " + buildString {
                        append(if (added == 0) "no new ROMs" else "$added new ROM(s) added")
                        if (removeMissing) append(if (removed == 0) ", none missing" else ", $removed missing removed")
                    },
                )
            }
            Timber.i("Library Manager scan complete for $platformId: $added new, $removed removed")
        }
    }

    fun scanAllConsoles(removeMissing: Boolean = false) {
        viewModelScope.launch {
            memoryCardRepository.getAll()
                .filter { it.enabled && (!it.treeUri.isNullOrBlank() || !it.romDirectory.isNullOrBlank()) }
                .forEach { scanConsole(it.platformId, removeMissing) }
        }
    }

    // ── ROM Root Access (managed root folders) ────────────────────────────────────
    //
    // The ROM roots live here now (moved in from the old Folder Access screen): add / remove /
    // re-link, with live grant status. Re-linking a root restores every console under it at once.

    fun refreshRomRoots() {
        viewModelScope.launch {
            val persisted = SafGrants.persistedReadUris(context.contentResolver)
            val rows = romRootRepository.getAll().map { uri ->
                RootFolderRow(uri, rootDisplayName(uri), SafGrants.linkStatus(uri, persisted) == FolderLinkStatus.LINKED)
            }
            _scratch.update { it.copy(romRoots = rows) }
        }
    }

    fun addRomRoot(uri: Uri) {
        viewModelScope.launch {
            // Read+write: the windows library auto-creates <root>/windows and its import/
            // drop-folder; older read-only roots degrade to find-only (WindowsLibrarySetup).
            romRootRepository.persist(uri, writable = true)
            // The roots flow collector picks up the change and refreshes the rows.
            romRootRepository.add(uri.toString())
        }
    }

    fun removeRomRoot(treeUri: String) {
        viewModelScope.launch {
            romRootRepository.remove(treeUri)
        }
    }

    private var pendingRelinkRomRoot: String? = null

    fun beginRelinkRomRoot(treeUri: String): Uri? {
        pendingRelinkRomRoot = treeUri
        return runCatching { Uri.parse(treeUri) }.getOrNull()
    }

    fun onRomRootRelinkPicked(uri: Uri?) {
        val old = pendingRelinkRomRoot ?: return
        pendingRelinkRomRoot = null
        if (uri == null) return
        viewModelScope.launch {
            romRootRepository.persist(uri)
            romRootRepository.replace(old, uri.toString())
            refreshRomRoots()
        }
    }

    // ── Import PC Games ───────────────────────────────────────────────────────────
    //
    // PFP is a frontend for PC launchers (Winlator, BannerHub, GameHub Lite, GameNative), never
    // the PC runtime. This section shows which supported launchers are installed and lets the
    // user pull already-captured games (arrived via pin / INSTALL_SHORTCUT) into a collection
    // named after the launcher. Direct per-launcher scanning is layered on via adapters.
    fun openImportPcGames() {
        // Entering the PC flow is PC intent — make sure the card and its folders exist.
        viewModelScope.launch { runCatching { windowsLibrarySetup.ensure() } }
        val pm = context.packageManager
        val launchers = PcLauncherCatalog.entries.map { def ->
            // Fingerprint-verified: GameHub-family variants ship under genuine AnTuTu/PUBG/Genshin
            // package names, so a package match alone would flag the real apps as launchers.
            val installedPkg = PcLauncherCatalog.verifiedInstalledPackage(def, pm)
            PcLauncherRow(
                type        = def.type,
                name        = def.displayName,
                installed   = installedPkg != null,
                packageName = installedPkg,
                canAddById  = PcLauncherAdapters.forType(def.type) != null,
            )
        }
        _scratch.update {
            it.copy(
                step           = LibraryStep.IMPORT_PC,
                pcLaunchers    = launchers,
                isHomeLauncher = launcherShortcutRepository.isDefaultLauncher(),
                returnFocusKey = IMPORT_PC_FOCUS_KEY,
            )
        }
    }

    /** Re-reads the Home-app status (after returning from the role/settings request). */
    fun refreshHomeStatus() {
        _scratch.update { it.copy(isHomeLauncher = launcherShortcutRepository.isDefaultLauncher()) }
    }

    /** Intent that lets the user make PFP the Home app (role request on Q+, else Home settings). */
    fun homeRoleIntent(): Intent = launcherShortcutRepository.homeRoleRequestIntent()

    /** Builds and starts a launcher's game intent immediately, to verify the id before saving. */
    fun testLaunchPcGame(row: PcLauncherRow, id: String, source: String?) {
        val pkg = row.packageName ?: return
        val intent = PcLauncherAdapters.forType(row.type, context.packageManager)?.buildLaunchIntent(pkg, id, source)
        if (intent == null) {
            _scratch.update { it.copy(message = INVALID_GAME_ID_MESSAGE) }
            return
        }
        runCatching { context.startActivity(intent) }.onFailure { e ->
            Timber.e(e, "PC test launch failed for ${row.name}")
            _scratch.update {
                it.copy(message = "Test launch failed: ${e.message}. The launcher may block external starts.")
            }
        }
    }

    /**
     * Adds a PC game by id: builds the launch intent, stores it, and files it in the Windows card.
     * The name is required — launchers keep their local libraries private, so PFP cannot resolve
     * a title from the id; the user copies the id from the game's page where its name is visible.
     */
    fun addPcGameById(row: PcLauncherRow, id: String, title: String?, source: String?) {
        val pkg = row.packageName ?: return
        val displayName = title?.trim().orEmpty()
        if (displayName.isBlank()) {
            _scratch.update { it.copy(message = "Enter the game's name — it's shown next to the ID in ${row.name}.") }
            return
        }
        val intent = PcLauncherAdapters.forType(row.type, context.packageManager)?.buildLaunchIntent(pkg, id, source)
        if (intent == null) {
            _scratch.update { it.copy(message = INVALID_GAME_ID_MESSAGE) }
            return
        }
        val intentUri = intent.toUri(Intent.URI_INTENT_SCHEME)
        viewModelScope.launch {
            runCatching {
                if (gameRepository.getByIntentUri(intentUri) == null &&
                    findWindowsGame(pkg, displayName) == null
                ) {
                    gameRepository.upsert(
                        Game(
                            title           = displayName,
                            platformId      = WINDOWS_PLATFORM_ID,
                            packageName     = pkg,
                            isManualEntry   = true,
                            contentType     = GameContentType.GAME,
                            launchIntentUri = intentUri,
                        )
                    )
                }
                ensureWindowsCard()
            }.fold(
                onSuccess = { _scratch.update { it.copy(message = "\"$displayName\" added to Windows Games.") } },
                onFailure = { e ->
                    Timber.e(e, "Add PC game by id failed for ${row.name}")
                    _scratch.update { it.copy(message = "Couldn't add game: ${e.message}") }
                },
            )
        }
    }

    /**
     * Runs the shared full PC scan (setup self-heal, OS pin sweep, `<windows>/import/` exports,
     * emu folder reconcile) — the same pass the XMB card's "Scan This Console" uses.
     */
    fun scanPcGamesFolder() {
        viewModelScope.launch {
            val report = runCatching { pcGameScanner.scan() }
                .onFailure { Timber.e(it, "PC scan failed") }
                .getOrNull()
            if (report == null) {
                _scratch.update { it.copy(message = "PC scan failed — see the log.") }
                return@launch
            }
            if (report.newGames > 0) ensureWindowsCard()
            _scratch.update { it.copy(message = report.message) }

            // Offer to fill in any emu folder that has steam_settings but no achievements schema;
            // each write is gated per game by the prompt (No / Yes / Yes to All for this scan).
            schemaPrompts.start(report.emu.missingSchema) { outcome ->
                if (outcome.generated > 0 || outcome.failed > 0) {
                    _scratch.update {
                        it.copy(message = schemaOutcomeMessage(outcome))
                    }
                }
            }
        }
    }

    private fun schemaOutcomeMessage(
        outcome: com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamSchemaPromptController.Outcome,
    ): String = buildString {
        append("Generated ${outcome.generated} achievement schema(s)")
        if (outcome.failed > 0) append(", ${outcome.failed} failed (check the Steam key)")
        append(". Play each game through the emulator to start earning coins.")
    }

    // ── Windows Games card helpers ────────────────────────────────────────────
    //
    // Every PC import lands directly in the Windows Games Memory Card — a virtual card (no ROM
    // directory) created on first import. Per-launcher collections are no longer created.

    private suspend fun ensureWindowsCard() {
        // Card + <ROM Root>/windows (+ import/) creation and directory assignment in one place.
        windowsLibrarySetup.ensure()
        memoryCardRepository.recountGames(WINDOWS_PLATFORM_ID)
    }

    // Title-level dedupe within the Windows card: the same game can arrive with different launch
    // handles (shortcut id via harvest, intent URI via folder scan), so handle-keyed lookups alone
    // can't converge re-imports.
    private suspend fun findWindowsGame(packageName: String, title: String): Game? {
        val key = normalizePcTitle(title)
        return gameRepository.getByPlatform(WINDOWS_PLATFORM_ID).firstOrNull {
            it.packageName == packageName && normalizePcTitle(it.displayTitle) == key
        }
    }

    private fun normalizePcTitle(title: String): String =
        title.lowercase().filter { it.isLetterOrDigit() }

    /** Moves one captured PC game (pin / INSTALL_SHORTCUT stray) into the Windows Games card. */
    fun importPcGame(row: PcGameRow) {
        viewModelScope.launch {
            runCatching {
                val game = gameRepository.getById(row.gameId) ?: error("Game not found")
                gameRepository.upsert(
                    game.copy(platformId = WINDOWS_PLATFORM_ID, contentType = GameContentType.GAME)
                )
                ensureWindowsCard()
            }.fold(
                onSuccess = { _scratch.update { it.copy(message = "\"${row.title}\" added to Windows Games.") } },
                onFailure = { e ->
                    Timber.e(e, "PC game import failed for ${row.gameId}")
                    _scratch.update { it.copy(message = "Couldn't import \"${row.title}\": ${e.message}") }
                },
            )
        }
    }

    /** Moves every captured PC game into the Windows Games card. */
    fun importAllPcGames() {
        val rows = uiState.value.pcGames
        if (rows.isEmpty()) return
        viewModelScope.launch {
            var added = 0
            rows.forEach { row ->
                runCatching {
                    val game = gameRepository.getById(row.gameId) ?: error("Game not found")
                    gameRepository.upsert(
                        game.copy(platformId = WINDOWS_PLATFORM_ID, contentType = GameContentType.GAME)
                    )
                    added++
                }.onFailure { Timber.e(it, "PC game import failed for ${row.gameId}") }
            }
            if (added > 0) ensureWindowsCard()
            _scratch.update { it.copy(message = "Imported $added PC game(s) into Windows Games.") }
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
                        gameRepository.observeByPlatform(platformId).first().mapNotNull { it.romPath }.toSet() +
                            scanTombstoneDao.getPathsForPlatform(platformId)
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

            // Windows is import-driven, not ROM-scanned, so the folder loop skips it (no
            // extensions). Auto-detect finishes with the shared Import PC pass instead: it
            // creates the Windows Memory Card, wires <root>/windows as its directory
            // (WindowsLibrarySetup.ensure), makes the import/ drop-folder, and imports any
            // exported games — the same pass as Import PC's folder scan.
            val hadWindowsCard = "windows" in haveCard
            val pcReport = runCatching { pcGameScanner.scan() }
                .onFailure { Timber.e(it, "Auto-detect PC scan failed") }
                .getOrNull()
            if (!hadWindowsCard && memoryCardRepository.getById("windows") != null) {
                haveCard.add("windows")
                newCards++
            }
            if (pcReport != null && pcReport.newGames > 0) {
                platformsWithGames.add("windows")
                totalAdded += pcReport.newGames
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

}
