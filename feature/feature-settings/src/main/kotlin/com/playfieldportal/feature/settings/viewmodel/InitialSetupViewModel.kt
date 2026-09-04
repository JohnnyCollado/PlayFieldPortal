package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.repository.FolderLinkStatus
import com.playfieldportal.core.data.repository.MediaRootKind
import com.playfieldportal.core.data.repository.MediaRootRepository
import com.playfieldportal.core.data.repository.RetroArchLink
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.core.data.repository.Vita3KLibrary
import com.playfieldportal.core.data.repository.SafGrants
import com.playfieldportal.feature.achievements.provider.steam.SteamRemoteDataSource
import com.playfieldportal.feature.artwork.MetadataApiKeyProvider
import com.playfieldportal.feature.artwork.api.ArtworkImportManager
import com.playfieldportal.feature.artwork.api.IgdbApi
import com.playfieldportal.feature.artwork.api.ScreenScraperApi
import com.playfieldportal.feature.artwork.api.SgdbApiKeyProvider
import com.playfieldportal.feature.artwork.importer.DetectedImportSource
import com.playfieldportal.feature.artwork.portable.PortableArtworkLibrary
import com.playfieldportal.feature.launcher.EmulatorAutoConfigService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The pages of the first-run wizard, in order. RetroArch and Vita3K exist only when installed. */
enum class SetupStep { WELCOME, ROM_ROOTS, MUSIC, VIDEO, PHOTO, ARTWORK, SERVICES, ACHIEVEMENTS, VITA, RETROARCH, FINISH }

/** A detected artwork source offered for the embedded quick-import (label + system count). */
@Immutable
data class ArtworkSourceUi(val label: String, val systems: Int)

@Immutable
data class InitialSetupUiState(
    val step: SetupStep = SetupStep.WELCOME,
    // True when RetroArch is installed — gates whether the RETROARCH page appears.
    val retroArchInstalled: Boolean = false,
    // True when Vita3K is installed — gates whether the VITA data-folder page appears.
    val vita3KInstalled: Boolean = false,
    // Multi-root lists per section (Library-Manager rows — a section can span several folders).
    val romRoots: List<RootFolderRow> = emptyList(),
    val musicRoots: List<RootFolderRow> = emptyList(),
    val videoRoots: List<RootFolderRow> = emptyList(),
    val photoRoots: List<RootFolderRow> = emptyList(),
    // Artwork is a SINGLE folder; sources under its import/ folder license the quick-import row.
    val artworkFolderName: String? = null,
    val artworkSources: List<ArtworkSourceUi> = emptyList(),
    // Services — connected state plus the public identity to show for it.
    val hasSgdb: Boolean = false,
    val igdbClientId: String = "",
    // ScreenScraper accounts only matter when the build ships dev credentials.
    val ssEnabled: Boolean = false,
    val ssUsername: String = "",
    val raUsername: String = "",
    val steamId64: String = "",
    // RetroArch cores link.
    val retroArchLinked: Boolean = false,
    val retroArchCoreCount: Int? = null,
    val retroArchDetecting: Boolean = false,
    // Vita3K data-folder (ux0) link — display name of the granted folder, null = not set.
    val vitaFolderName: String? = null,
    val message: String? = null,
    // Per-service validation results (\"Testing…\" / \"Valid …\" / \"Invalid …\"), shown inline.
    val igdbStatus: String? = null,
    val ssStatus: String? = null,
) {
    val hasIgdb: Boolean get() = igdbClientId.isNotBlank()

    /** 1-based page number within the reachable (RetroArch/Vita3K-gated) flow — hidden pages skip. */
    val stepNumber: Int
        get() {
            val order = SetupStep.entries.filter {
                (it != SetupStep.RETROARCH || retroArchInstalled) &&
                    (it != SetupStep.VITA || vita3KInstalled)
            }
            val idx = order.indexOf(step)
            return if (idx >= 0) idx + 1 else 1
        }
    val hasScreenScraper: Boolean get() = ssUsername.isNotBlank()
    val hasRetroAchievements: Boolean get() = raUsername.isNotBlank()
    val hasSteam: Boolean get() = steamId64.isNotBlank()
    val anyFolderSet: Boolean get() =
        romRoots.isNotEmpty() || musicRoots.isNotEmpty() || videoRoots.isNotEmpty() ||
            photoRoots.isNotEmpty() || artworkFolderName != null
}

// Typed intermediate groups so the combine stays compiler-checked — no positional
// Array<Any?> casts that silently shift when a flow is added or reordered.
@Immutable
private data class RootLists(
    val rom: List<RootFolderRow>,
    val music: List<RootFolderRow>,
    val video: List<RootFolderRow>,
    val photo: List<RootFolderRow>,
    val artwork: String?,   // artwork folder display name
    val vita: String?,      // Vita3K ux0 folder display name
)

@Immutable
private data class ServiceIdentities(
    val hasSgdb: Boolean,
    val igdbClientId: String,
    val ssUsername: String,
    val raUsername: String,
    val steamId64: String,
)

private const val RETROARCH_PACKAGE = "com.retroarch"

// Vita3K ships under one package name plus commonly-shared variants; any installed means its
// data-folder (ux0) page should be offered. Same set as KnownEmulatorCatalog.
private val VITA3K_PACKAGES = listOf("org.vita3k.emulator", "org.vita3k.emulator.ikhoeyZX")

/**
 * First-run setup wizard, broken into one task per page per the approved plan: Welcome → ROM
 * Roots → Music → Video → Photo → Artwork → Online Services → Vita* → RetroArch* → Finish
 * (* only when the matching app is installed). Each folder section is multi-root exactly like Settings ▸ Library
 * ROM Root Access and the Music/Video/Photo screens, artwork is one folder with an embedded
 * quick-import offer, and services mirror Settings ▸ Artwork/Shiba. Pure glue — every value is
 * stored through the same repository/provider the corresponding settings screen uses, so
 * anything configured here shows up there and vice versa. Everything is optional.
 */
@HiltViewModel
class InitialSetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val romRootRepository: RomRootRepository,
    private val mediaRootRepository: MediaRootRepository,
    private val artworkImportManager: ArtworkImportManager,
    private val retroArchLink: RetroArchLink,
    private val vita3KLibrary: Vita3KLibrary,
    private val autoConfig: EmulatorAutoConfigService,
    private val sgdbKeys: SgdbApiKeyProvider,
    private val metadataKeys: MetadataApiKeyProvider,
    private val achievementCredentials: AchievementCredentialsProvider,
    private val steamApi: SteamRemoteDataSource,
    private val igdbApi: IgdbApi,
    private val screenScraperApi: ScreenScraperApi,
    private val wizardMediaScanRunner: com.playfieldportal.feature.settings.media.WizardMediaScanRunner,
    private val romRootScanRunner: RomRootScanRunner,
    private val romScanner: com.playfieldportal.feature.library.scanner.RomScanner,
    private val folderHintResolver: com.playfieldportal.core.data.platform.PlatformFolderHintResolver,
    private val memoryCardRepository: com.playfieldportal.core.data.repository.MemoryCardRepository,
) : ViewModel() {

    // Wizard-local state (page + transient messages + RetroArch status); the folder/service rows
    // are mirrored from the stores so they never go stale.
    // ssEnabled used to be a build constant readable here; it is now stored state, so it starts
    // false and is filled in by the init block below alongside the other detected values.
    private val scratch = MutableStateFlow(InitialSetupUiState())

    // Detected artwork sources kept beside (not inside) UiState so state carries only display
    // data; aligned by index with artworkSources.
    private var detectedArtworkSources: List<DetectedImportSource> = emptyList()

    init {
        viewModelScope.launch {
            val ssEnabled = screenScraperApi.isEnabled()
            scratch.update {
                it.copy(
                    ssEnabled = ssEnabled,
                    retroArchInstalled = isRetroArchInstalled(),
                    vita3KInstalled = isVita3KInstalled(),
                )
            }
            readRetroArchState()
        }
    }

    // Display-name rows derive per-flow; grant status is snapshotted at emission time so a lost
    // grant (reinstall) reports ACCESS_LOST immediately, like the settings screens.
    // combine() is typed to 5 flows — the folder roots pack into one RootLists, then the Vita3K
    // ux0 folder is layered on top (keeping the typed lambda, never an Array<Any?> cast).
    private val rootLists = combine(
        combine(
            romRootRepository.roots,
            mediaRootRepository.roots(MediaRootKind.MUSIC),
            mediaRootRepository.roots(MediaRootKind.VIDEO),
            mediaRootRepository.roots(MediaRootKind.PHOTO),
            artworkImportManager.folderTreeUri,
        ) { rom, music, video, photo, artwork ->
            val persisted = SafGrants.persistedReadUris(context.contentResolver)
            RootLists(
                rom     = rom.toRows(persisted),
                music   = music.toRows(persisted),
                video   = video.toRows(persisted),
                photo   = photo.toRows(persisted),
                artwork = artwork?.let(::rootDisplayName),
                vita    = null,
            )
        },
        vita3KLibrary.ux0TreeUriFlow,
    ) { lists, vita -> lists.copy(vita = vita?.let(::rootDisplayName)) }

    private val serviceIdentities = combine(
        sgdbKeys.apiKeyFlow,
        metadataKeys.igdbClientIdFlow,
        metadataKeys.ssUsernameFlow,
        achievementCredentials.raUsernameFlow,
        achievementCredentials.steamId64Flow,
    ) { sgdbKey, igdbId, ssUser, raUser, steamId ->
        ServiceIdentities(
            hasSgdb      = !sgdbKey.isNullOrBlank(),
            igdbClientId = igdbId.orEmpty(),
            ssUsername   = ssUser.orEmpty(),
            raUsername   = raUser.orEmpty(),
            steamId64    = steamId.orEmpty(),
        )
    }

    val uiState: StateFlow<InitialSetupUiState> = combine(
        scratch, rootLists, serviceIdentities,
    ) { local, roots, services ->
        local.copy(
            romRoots       = roots.rom,
            musicRoots     = roots.music,
            videoRoots     = roots.video,
            photoRoots     = roots.photo,
            artworkFolderName = roots.artwork,
            vitaFolderName    = roots.vita,
            hasSgdb           = services.hasSgdb,
            igdbClientId      = services.igdbClientId,
            ssUsername        = services.ssUsername,
            raUsername        = services.raUsername,
            steamId64         = services.steamId64,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), scratch.value)

    private fun List<String>.toRows(persisted: Set<String>): List<RootFolderRow> =
        map { uri ->
            RootFolderRow(
                treeUri = uri,
                name = rootDisplayName(uri),
                linked = SafGrants.linkStatus(uri, persisted) == FolderLinkStatus.LINKED,
            )
        }

    private fun isRetroArchInstalled(): Boolean =
        runCatching { context.packageManager.getPackageInfo(RETROARCH_PACKAGE, 0) }.isSuccess

    private fun isVita3KInstalled(): Boolean =
        VITA3K_PACKAGES.any {
            runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
        }

    // ── Step navigation ───────────────────────────────────────────────────────

    /** Back to Welcome with transient state cleared. See old resetWizard contract. */
    fun resetWizard() = scratch.update {
        it.copy(
            step = SetupStep.WELCOME, message = null, igdbStatus = null, ssStatus = null,
            retroArchDetecting = false,
        )
    }

    /** The steps in play — defeats the RetroArch and Vita3K pages when their app isn't installed. */
    private fun reachableSteps(): List<SetupStep> =
        SetupStep.entries.filter {
            (it != SetupStep.RETROARCH || scratch.value.retroArchInstalled) &&
                (it != SetupStep.VITA || scratch.value.vita3KInstalled)
        }

    fun nextStep() {
        val order = reachableSteps()
        val next = order.getOrNull(order.indexOf(scratch.value.step) + 1)
        scratch.update {
            it.copy(step = next ?: it.step, message = null, igdbStatus = null, ssStatus = null)
        }
    }

    /** Steps one page back. Returns false when already on the first page (caller exits). */
    fun previousStep(): Boolean {
        if (scratch.value.step == SetupStep.WELCOME) return false
        val order = reachableSteps()
        val idx = order.indexOf(scratch.value.step)
        if (idx <= 0) return false
        scratch.update {
            it.copy(
                step = order[idx - 1],
                message = null, igdbStatus = null, ssStatus = null,
            )
        }
        return true
    }

    // ── ROM roots (multi-root, Library-Manager style) ──────────────────────────

    fun addRomRoot(uri: Uri) {
        viewModelScope.launch {
            romRootRepository.persist(uri, writable = true)
            romRootRepository.add(uri.toString())
            // Auto-detect + scan on the runner's own scope so it survives the wizard closing —
            // this pass creates the consoles and stamps lastScannedAt.
            romRootScanRunner.kickoff()
        }
    }

    fun removeRomRoot(treeUri: String) {
        viewModelScope.launch { romRootRepository.remove(treeUri) }
    }

    fun relinkRomRoot(oldTreeUri: String, newUri: Uri) {
        viewModelScope.launch {
            romRootRepository.persist(newUri)
            romRootRepository.replace(oldTreeUri, newUri.toString())
            romRootScanRunner.kickoff()
        }
    }

    fun rescanRomRoots() = romRootScanRunner.kickoff()

    /**
     * B3: offer to scaffold one ES-DE subfolder per supported platform under the first ROM root
     * ("where do I put my ROMs?"). Same call Library Manager's folder-setup flow makes — the
     * scanner then auto-creates Memory Cards for any folder that maps to a platform.
     */
    fun createStandardRomFolders() {
        val firstRoot = scratch.value.romRoots.firstOrNull()?.treeUri ?: return
        viewModelScope.launch {
            val names = memoryCardRepository.availablePlatformCatalog()
                .map { folderHintResolver.esDeFolderName(it.id) }
                .filter { it.isNotBlank() && it != "android" }
                .distinct()
            val result = romScanner.createSubfolders(firstRoot, names)
            scratch.update {
                it.copy(
                    message = "Created ${result.created} console folder(s)" +
                        (if (result.existing > 0) " (${result.existing} already there)" else "") +
                        ". Copy your games into the matching folders.",
                )
            }
        }
    }

    // ── Music / Video / Photo roots (multi-root) ───────────────────────────────

    fun addMediaRoot(kind: MediaRootKind, uri: Uri) {
        viewModelScope.launch {
            mediaRootRepository.persist(uri)
            mediaRootRepository.add(kind, uri.toString())
            wizardMediaScanRunner.kickoff(kind)
        }
    }

    fun removeMediaRoot(kind: MediaRootKind, treeUri: String) {
        viewModelScope.launch {
            mediaRootRepository.remove(kind, treeUri)
            // Reconcile the library rows with the remaining roots (drops the removed row) and
            // rescan the survivors.
            wizardMediaScanRunner.kickoff(kind)
        }
    }

    fun relinkMediaRoot(kind: MediaRootKind, oldTreeUri: String, newUri: Uri) {
        viewModelScope.launch {
            mediaRootRepository.persist(newUri)
            mediaRootRepository.replace(kind, oldTreeUri, newUri.toString())
            wizardMediaScanRunner.kickoff(kind)
        }
    }

    fun rescanMediaRoot(kind: MediaRootKind) = wizardMediaScanRunner.kickoff(kind)

    // ── Artwork (single folder) ────────────────────────────────────────────────

    /** Links [uri] as the artwork folder and offers a quick-import when import/ holds sources. */
    fun onArtworkFolderPicked(uri: Uri) {
        viewModelScope.launch {
            val result = artworkImportManager.linkFolder(uri)
            if (result == null) {
                scratch.update {
                    it.copy(message = "Could not set up an artwork library in that folder. Pick a writable folder.")
                }
                return@launch
            }
            // Zero-copy adoption of anything already in the folder (same pass Settings ▸ Artwork
            // Import runs on pick), then scan for importable sources.
            val scan = runCatching { artworkImportManager.relinkLibrary() }.getOrNull()
            val sources = runCatching { artworkImportManager.detectSources() }.getOrDefault(emptyList())
            detectedArtworkSources = sources
            scratch.update {
                it.copy(
                    message = buildString {
                        append(
                            if (result.existingLibrary) "Existing artwork library reconnected."
                            else "Artwork library created."
                        )
                        if (scan != null && scan.gamesLinked > 0) {
                            append(" ${scan.gamesLinked} game(s) linked from files already in the folder.")
                        } else if (sources.isEmpty()) {
                            append(" Place other launchers' media under its import/ folder to gather it here.")
                        }
                    },
                    artworkSources = sources.map { s -> ArtworkSourceUi(s.label, s.systems.size) },
                )
            }
        }
    }

    /** Releases the link (files are never touched) and clears the import offer. */
    fun forgetArtworkFolder() {
        viewModelScope.launch {
            artworkImportManager.forgetFolder()
            detectedArtworkSources = emptyList()
            scratch.update {
                it.copy(
                    artworkSources = emptyList(),
                    message = "Artwork folder released — files on disk were not touched.",
                )
            }
        }
    }

    /** Copies the first detected source into the artwork library (never moves first-run files). */
    fun importArtworkNow() {
        val detected = detectedArtworkSources.firstOrNull()
        val label = scratch.value.artworkSources.firstOrNull()?.label
        if (detected == null) {
            scratch.update {
                it.copy(message = "Nothing to import yet — place files under the artwork folder's import/ directory.")
            }
            return
        }
        viewModelScope.launch {
            val plan = runCatching { artworkImportManager.buildPlan(detected) }.getOrNull()
            if (plan == null) {
                scratch.update { it.copy(message = "Could not read that import source.") }
                return@launch
            }
            if (plan.itemCount == 0) {
                scratch.update { it.copy(message = "Nothing to import — everything is already present.") }
                return@launch
            }
            artworkImportManager.startImport(plan, PortableArtworkLibrary.Transfer.COPY)
            scratch.update {
                it.copy(
                    message = "Importing \"${label ?: plan.sourceLabel}\" — progress shows in notifications; details land in Settings ▸ Artwork Import.",
                )
            }
        }
    }

    // ── RetroArch cores folder ─────────────────────────────────────────────────

    fun linkRetroArch(uri: Uri) {
        viewModelScope.launch {
            scratch.update { it.copy(retroArchDetecting = true) }
            retroArchLink.save(uri)
            autoConfig.runOnStartup()
            readRetroArchState("RetroArch linked — installed cores are now offered in Emulators.")
        }
    }

    fun redetectRetroArchCores() {
        if (!scratch.value.retroArchLinked) return
        viewModelScope.launch {
            scratch.update { it.copy(retroArchDetecting = true) }
            autoConfig.runOnStartup()
            readRetroArchState("RetroArch cores re-checked.")
        }
    }

    fun unlinkRetroArch() {
        viewModelScope.launch {
            retroArchLink.clear()
            autoConfig.runOnStartup()
            scratch.update {
                it.copy(
                    retroArchLinked = false,
                    retroArchCoreCount = null,
                    retroArchDetecting = false,
                    message = "RetroArch link removed — all curated cores will be offered (unverified).",
                )
            }
        }
    }

    private suspend fun readRetroArchState(doneMessage: String? = null) {
        val linked = retroArchLink.isLinked()
        val installed = if (linked) retroArchLink.installedCoreFiles() else null
        scratch.update {
            it.copy(
                retroArchDetecting = false,
                retroArchLinked = linked,
                retroArchCoreCount = installed?.size,
                message = doneMessage ?: it.message,
            )
        }
    }

    // ── Vita3K data folder (ux0) ───────────────────────────────────────────────

    /** Grants the Vita3K `ux0` folder (persisting the SAF read grant, the same store the
     *  Library Manager reads) so installed Vita titles can be discovered and scanned later. */
    fun linkVitaFolder(uri: Uri) {
        viewModelScope.launch {
            vita3KLibrary.setUx0Folder(uri)
            scratch.update {
                it.copy(
                    message = "Vita3K data folder set. Installed titles can be scanned from the " +
                        "PS Vita Memory Card in Library Manager.",
                    vitaFolderName = rootDisplayName(uri.toString()),
                )
            }
        }
    }

    /** Releases the Vita3K data-folder link (files are never touched). */
    fun forgetVitaFolder() {
        viewModelScope.launch {
            vita3KLibrary.clear()
            scratch.update {
                it.copy(message = "Vita3K data folder released — files on disk were not touched.")
            }
        }
    }

    // ── Services ──────────────────────────────────────────────────────────────

    fun connectSgdb(apiKey: String) {
        if (apiKey.isBlank()) return
        viewModelScope.launch {
            sgdbKeys.saveKey(apiKey)
            scratch.update { it.copy(message = "SteamGridDB connected") }
        }
    }

    fun connectIgdb(clientId: String, clientSecret: String) {
        if (clientId.isBlank() || clientSecret.isBlank()) return
        viewModelScope.launch {
            metadataKeys.saveIgdbCredentials(clientId, clientSecret)
            scratch.update { it.copy(message = "IGDB connected", igdbStatus = null) }
        }
    }

    /** Same live check as Settings ▸ Artwork (shared via [ServiceConnectors]). */
    fun testIgdbCredentials(clientId: String, clientSecret: String) {
        viewModelScope.launch {
            scratch.update { it.copy(igdbStatus = "Testing…") }
            val status = ServiceConnectors.testIgdb(igdbApi, clientId, clientSecret)
            scratch.update { it.copy(igdbStatus = status) }
        }
    }

    fun dismissIgdbStatus() = scratch.update { it.copy(igdbStatus = null) }

    /** Same live check as Settings ▸ Artwork (shared via [ServiceConnectors]). */
    fun testSsCredentials(username: String, password: String) {
        viewModelScope.launch {
            scratch.update { it.copy(ssStatus = "Testing…") }
            val status = ServiceConnectors.testScreenScraper(screenScraperApi, username, password)
            scratch.update { it.copy(ssStatus = status) }
        }
    }

    fun dismissSsStatus() = scratch.update { it.copy(ssStatus = null) }

    fun connectScreenScraper(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) return
        viewModelScope.launch {
            metadataKeys.saveSsCredentials(username, password)
            scratch.update { it.copy(message = "ScreenScraper connected", ssStatus = null) }
        }
    }

    fun connectRetroAchievements(username: String, apiKey: String) {
        if (username.isBlank() || apiKey.isBlank()) return
        viewModelScope.launch {
            achievementCredentials.saveRetroAchievements(username, apiKey)
            achievementCredentials.setEnabled(true)
            scratch.update { it.copy(message = "RetroAchievements connected") }
        }
    }

    /** Same connect flow as Settings ▸ Shiba Coins (shared via [ServiceConnectors]). */
    fun connectSteam(idOrVanity: String, apiKey: String) {
        if (idOrVanity.isBlank() || apiKey.isBlank()) return
        viewModelScope.launch {
            achievementCredentials.setEnabled(true)
            val message = ServiceConnectors.connectSteam(
                achievementCredentials, steamApi, idOrVanity, apiKey,
            )
            scratch.update { it.copy(message = message) }
        }
    }

    fun dismissMessage() = scratch.update { it.copy(message = null) }
}
