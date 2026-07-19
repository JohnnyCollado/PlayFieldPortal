package com.playfieldportal.feature.settings.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.repository.MediaRootKind
import com.playfieldportal.core.data.repository.MediaRootRepository
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.feature.artwork.MetadataApiKeyProvider
import com.playfieldportal.feature.artwork.api.ArtworkImportManager
import com.playfieldportal.feature.artwork.api.IgdbApi
import com.playfieldportal.feature.artwork.api.ScreenScraperApi
import com.playfieldportal.feature.artwork.api.SgdbApiKeyProvider
import com.playfieldportal.feature.achievements.provider.steam.SteamRemoteDataSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The four pages of the first-run wizard, in order. */
enum class SetupStep { WELCOME, FOLDERS, SERVICES, FINISH }

data class InitialSetupUiState(
    val step: SetupStep = SetupStep.WELCOME,
    // Folder slots — null until the user picks one (display names, not URIs).
    val romRootName: String? = null,
    val musicRootName: String? = null,
    val videoRootName: String? = null,
    val photoRootName: String? = null,
    val artworkFolderName: String? = null,
    // Services — connected state plus the public identity to show for it.
    val hasSgdb: Boolean = false,
    val igdbClientId: String = "",
    // ScreenScraper accounts only matter when the build ships dev credentials.
    val ssEnabled: Boolean = false,
    val ssUsername: String = "",
    val raUsername: String = "",
    val steamId64: String = "",
    val message: String? = null,
    // Per-service validation results ("Testing…" / "Valid …" / "Invalid …"), shown inline.
    val igdbStatus: String? = null,
    val ssStatus: String? = null,
) {
    val hasIgdb: Boolean get() = igdbClientId.isNotBlank()
    val hasScreenScraper: Boolean get() = ssUsername.isNotBlank()
    val hasRetroAchievements: Boolean get() = raUsername.isNotBlank()
    val hasSteam: Boolean get() = steamId64.isNotBlank()
    val anyFolderSet: Boolean get() = listOf(
        romRootName, musicRootName, videoRootName, photoRootName, artworkFolderName,
    ).any { it != null }
}

// Typed intermediate groups so the final combine stays compiler-checked — no positional
// Array<Any?> casts that silently shift when a flow is added or reordered.
private data class FolderNames(
    val rom: String?,
    val music: String?,
    val video: String?,
    val photo: String?,
    val artwork: String?,
)

private data class ServiceIdentities(
    val hasSgdb: Boolean,
    val igdbClientId: String,
    val ssUsername: String,
    val raUsername: String,
    val steamId64: String,
)

/**
 * First-run setup wizard: root folders (ROMs, Music, Video, Photo, Artwork) and service
 * credentials (SteamGridDB, IGDB, ScreenScraper, RetroAchievements, Steam). Pure glue — every
 * value is stored through the same repository/provider the corresponding settings screen uses,
 * so anything configured here shows up there and vice versa. Everything is optional.
 */
@HiltViewModel
class InitialSetupViewModel @Inject constructor(
    private val romRootRepository: RomRootRepository,
    private val mediaRootRepository: MediaRootRepository,
    private val artworkImportManager: ArtworkImportManager,
    private val sgdbKeys: SgdbApiKeyProvider,
    private val metadataKeys: MetadataApiKeyProvider,
    private val achievementCredentials: AchievementCredentialsProvider,
    private val steamApi: SteamRemoteDataSource,
    private val igdbApi: IgdbApi,
    private val screenScraperApi: ScreenScraperApi,
    private val wizardMediaScanRunner: com.playfieldportal.feature.settings.media.WizardMediaScanRunner,
) : ViewModel() {

    // Wizard-local state (page + transient messages); everything else mirrors the stores.
    private val scratch = MutableStateFlow(InitialSetupUiState(ssEnabled = screenScraperApi.isEnabled))

    // Display names derive per-flow, so a URI is only re-parsed when that root actually changes.
    private val folderNames = combine(
        romRootRepository.roots,
        mediaRootRepository.observe(MediaRootKind.MUSIC),
        mediaRootRepository.observe(MediaRootKind.VIDEO),
        mediaRootRepository.observe(MediaRootKind.PHOTO),
        artworkImportManager.folderTreeUri,
    ) { romRoots, music, video, photo, artwork ->
        FolderNames(
            rom     = romRoots.firstOrNull()?.let(::rootDisplayName),
            music   = music?.let(::rootDisplayName),
            video   = video?.let(::rootDisplayName),
            photo   = photo?.let(::rootDisplayName),
            artwork = artwork?.let(::rootDisplayName),
        )
    }

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
        scratch, folderNames, serviceIdentities,
    ) { local, folders, services ->
        local.copy(
            romRootName       = folders.rom,
            musicRootName     = folders.music,
            videoRootName     = folders.video,
            photoRootName     = folders.photo,
            artworkFolderName = folders.artwork,
            hasSgdb           = services.hasSgdb,
            igdbClientId      = services.igdbClientId,
            ssUsername        = services.ssUsername,
            raUsername        = services.raUsername,
            steamId64         = services.steamId64,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), scratch.value)

    // ── Step navigation ───────────────────────────────────────────────────────

    /**
     * Back to the Welcome page with transient state cleared. Called when the screen leaves
     * composition: the ViewModel is activity-scoped and outlives the overlay, so without this a
     * re-run from Settings would resume wherever the wizard was last closed.
     */
    fun resetWizard() = scratch.update {
        it.copy(step = SetupStep.WELCOME, message = null, igdbStatus = null, ssStatus = null)
    }

    fun nextStep() {
        scratch.update { s ->
            val next = SetupStep.entries.getOrNull(s.step.ordinal + 1) ?: s.step
            s.copy(step = next, message = null, igdbStatus = null, ssStatus = null)
        }
    }

    /** Steps one page back. Returns false when already on the first page (caller exits). */
    fun previousStep(): Boolean {
        val current = scratch.value.step
        if (current == SetupStep.WELCOME) return false
        scratch.update {
            it.copy(
                step = SetupStep.entries[current.ordinal - 1],
                message = null, igdbStatus = null, ssStatus = null,
            )
        }
        return true
    }

    // ── Folders ───────────────────────────────────────────────────────────────

    /**
     * ROM root: persisted SAF grant + added to the root list. The grant is read+write, exactly
     * like Library Manager's add-root path, so a wizard-configured root supports the ES-DE
     * folder setup the same way a Settings-configured one does.
     */
    fun onRomRootPicked(uri: Uri) {
        viewModelScope.launch {
            romRootRepository.persist(uri, writable = true)
            romRootRepository.add(uri.toString())
        }
    }

    fun onMediaRootPicked(kind: MediaRootKind, uri: Uri) {
        viewModelScope.launch {
            mediaRootRepository.persist(uri)
            mediaRootRepository.set(kind, uri.toString())
            // The settings screens pair set-root with an immediate rescan — that scan is what
            // creates the library row and clears the XMB's "+ Add" getting-started row. Mirror
            // it here, on a scope that survives the wizard closing.
            wizardMediaScanRunner.kickoff(kind)
        }
    }

    /** Artwork folder: full portable-library link (manifest + read/write grant). */
    fun onArtworkFolderPicked(uri: Uri) {
        viewModelScope.launch {
            val result = artworkImportManager.linkFolder(uri)
            if (result == null) {
                scratch.update {
                    it.copy(message = "Could not set up an artwork library in that folder. Pick a writable folder.")
                }
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
