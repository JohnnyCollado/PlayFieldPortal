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
import timber.log.Timber
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

/**
 * First-run setup wizard: root folders (ROMs, Music, Video, Photo, Artwork) and service
 * credentials (SteamGridDB, ScreenScraper, RetroAchievements, Steam). Pure glue — every value
 * is stored through the same repository/provider the corresponding settings screen uses, so
 * anything configured here shows up there and vice versa. Everything is optional.
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
) : ViewModel() {

    // Wizard-local state (page + transient message); everything else mirrors the stores.
    private val scratch = MutableStateFlow(InitialSetupUiState(ssEnabled = screenScraperApi.isEnabled))

    val uiState: StateFlow<InitialSetupUiState> = combine(
        scratch,
        romRootRepository.roots,
        mediaRootRepository.observe(MediaRootKind.MUSIC),
        mediaRootRepository.observe(MediaRootKind.VIDEO),
        mediaRootRepository.observe(MediaRootKind.PHOTO),
        artworkImportManager.folderTreeUri,
        sgdbKeys.apiKeyFlow,
        metadataKeys.igdbClientIdFlow,
        metadataKeys.ssUsernameFlow,
        achievementCredentials.raUsernameFlow,
        achievementCredentials.steamId64Flow,
    ) { values ->
        val local = values[0] as InitialSetupUiState
        @Suppress("UNCHECKED_CAST")
        val romRoots = values[1] as List<String>
        local.copy(
            romRootName       = romRoots.firstOrNull()?.let(::rootDisplayName),
            musicRootName     = (values[2] as String?)?.let(::rootDisplayName),
            videoRootName     = (values[3] as String?)?.let(::rootDisplayName),
            photoRootName     = (values[4] as String?)?.let(::rootDisplayName),
            artworkFolderName = (values[5] as String?)?.let(::rootDisplayName),
            hasSgdb           = !(values[6] as String?).isNullOrBlank(),
            igdbClientId      = (values[7] as String?).orEmpty(),
            ssUsername        = (values[8] as String?).orEmpty(),
            raUsername        = (values[9] as String?).orEmpty(),
            steamId64         = (values[10] as String?).orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), scratch.value)

    // ── Step navigation ───────────────────────────────────────────────────────

    /**
     * Back to the Welcome page with transient state cleared. Called when the screen leaves
     * composition: the ViewModel is activity-scoped and outlives the overlay, so without this a
     * re-run from Settings would resume wherever the wizard was last closed.
     */
    fun resetWizard() {
        scratch.update {
            it.copy(step = SetupStep.WELCOME, message = null, igdbStatus = null, ssStatus = null)
        }
    }

    fun nextStep() {
        scratch.update { s ->
            val next = SetupStep.entries.getOrNull(s.step.ordinal + 1) ?: s.step
            s.copy(step = next, message = null)
        }
    }

    /** Steps one page back. Returns false when already on the first page (caller exits). */
    fun previousStep(): Boolean {
        val current = scratch.value.step
        if (current == SetupStep.WELCOME) return false
        scratch.update { it.copy(step = SetupStep.entries[current.ordinal - 1], message = null) }
        return true
    }

    // ── Folders ───────────────────────────────────────────────────────────────

    /** ROM root: persisted read grant + added to the root list (same as Library Manager). */
    fun onRomRootPicked(uri: Uri) {
        viewModelScope.launch {
            romRootRepository.persist(uri)
            romRootRepository.add(uri.toString())
        }
    }

    fun onMediaRootPicked(kind: MediaRootKind, uri: Uri) {
        viewModelScope.launch {
            mediaRootRepository.persist(uri)
            mediaRootRepository.set(kind, uri.toString())
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

    /** Same live check as Settings ▸ Artwork: a Twitch token request with the entered pair. */
    fun testIgdbCredentials(clientId: String, clientSecret: String) {
        viewModelScope.launch {
            scratch.update { it.copy(igdbStatus = "Testing…") }
            val ok = igdbApi.testCredentials(clientId.trim(), clientSecret.trim())
            scratch.update {
                it.copy(igdbStatus = if (ok) "Valid" else "Invalid — check Client ID and Secret")
            }
        }
    }

    fun dismissIgdbStatus() = scratch.update { it.copy(igdbStatus = null) }

    /** Same live check as Settings ▸ Artwork: fetches the account's thread/quota limits. */
    fun testSsCredentials(username: String, password: String) {
        viewModelScope.launch {
            scratch.update { it.copy(ssStatus = "Testing…") }
            val user = screenScraperApi.fetchUserInfo(username.trim(), password.trim())
            scratch.update {
                it.copy(ssStatus = if (user != null) {
                    buildString {
                        append("Valid")
                        user.maxThreads?.let { t -> append(" — $t thread${if (t == "1") "" else "s"}") }
                        user.maxRequestsPerDay?.let { q -> append(", $q requests/day") }
                    }
                } else "Invalid — check username and password")
            }
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

    /** Mirrors AchievementsSettingsViewModel.connectSteam: a vanity name is resolved to an id. */
    fun connectSteam(idOrVanity: String, apiKey: String) {
        if (idOrVanity.isBlank() || apiKey.isBlank()) return
        viewModelScope.launch {
            val input = idOrVanity.trim()
            val key = apiKey.trim()
            achievementCredentials.saveSteam(input, key)
            achievementCredentials.setEnabled(true)
            if (input.matches(STEAM_ID64)) {
                scratch.update { it.copy(message = "Steam connected") }
                return@launch
            }
            val resolved = steamApi.resolveVanity(input)
            if (resolved != null) {
                achievementCredentials.saveSteam(resolved, key)
                scratch.update { it.copy(message = "Steam connected — resolved \"$input\"") }
            } else {
                scratch.update {
                    it.copy(message = "Key saved, but \"$input\" couldn't be resolved. Enter your SteamID64.")
                }
            }
        }
    }

    fun dismissMessage() = scratch.update { it.copy(message = null) }

    companion object {
        private val STEAM_ID64 = Regex("\\d{17}")
    }
}
