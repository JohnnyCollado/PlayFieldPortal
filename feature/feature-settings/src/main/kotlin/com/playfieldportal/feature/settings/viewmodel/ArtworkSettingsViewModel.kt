package com.playfieldportal.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.feature.artwork.MetadataApiKeyProvider
import com.playfieldportal.feature.artwork.api.ArtworkRepository
import com.playfieldportal.feature.artwork.api.ArtworkScrapePreferences
import com.playfieldportal.feature.artwork.api.ArtworkStatus
import com.playfieldportal.feature.artwork.api.IgdbApi
import com.playfieldportal.feature.artwork.api.ScrapeProgress
import com.playfieldportal.feature.artwork.api.ScreenScraperApi
import com.playfieldportal.feature.artwork.api.SgdbApiKeyProvider
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

data class ArtworkSettingsUiState(
    val hasApiKey: Boolean = false,
    val apiKeyMasked: String = "",
    val hasIgdbCredentials: Boolean = false,
    val igdbClientId: String = "",
    val igdbCredentialStatus: String? = null,
    // ScreenScraper: ssEnabled = dev credentials compiled into this build (feature works at all);
    // the user account is optional and only raises rate limits/quota.
    val ssEnabled: Boolean = false,
    val hasSsCredentials: Boolean = false,
    val ssUsername: String = "",
    val ssCredentialStatus: String? = null,
    val status: ArtworkStatus = ArtworkStatus(),
    val isLoadingStatus: Boolean = false,
    val isScraping: Boolean = false,
    val scrapeCurrent: Int = 0,
    val scrapeTotal: Int = 0,
    val scrapeSucceeded: Int = 0,
    val scrapeFailed: Int = 0,
    val scrapeTitle: String = "",
    val scrapeSource: String = "",
    val scrapeAsset: String = "",
    val summary: String? = null,
    val confirmRescrapeAll: Boolean = false,
    val diskCacheSizeMb: String = "0 MB",
    val preferredGridStyle: String = "Any",
    val downloadHeroes: Boolean = true,
    val downloadLogos: Boolean = true,
    val downloadManuals: Boolean = false,
    val downloadVideoSnaps: Boolean = false,
    val preferSteamGridDbHeroes: Boolean = false,
    // Portable artwork folder is configured but its access grant died (SD removed, permission
    // revoked) — surfaces a warning on the Artwork Folder & Import row.
    val artworkFolderGrantDead: Boolean = false,
)

@HiltViewModel
class ArtworkSettingsViewModel @Inject constructor(
    private val sgdbKeyProvider: SgdbApiKeyProvider,
    private val metadataKeyProvider: MetadataApiKeyProvider,
    private val artworkRepository: ArtworkRepository,
    private val scrapePreferences: ArtworkScrapePreferences,
    private val igdbApi: IgdbApi,
    private val screenScraperApi: ScreenScraperApi,
    private val artworkFolderRepository: com.playfieldportal.core.data.repository.ArtworkFolderRepository,
) : ViewModel() {

    private val _extra = MutableStateFlow(ArtworkSettingsUiState())

    init {
        // Startup grant check (§17): a configured folder whose grant died gets a visible
        // warning instead of silently broken artwork.
        viewModelScope.launch {
            val configured = artworkFolderRepository.getTreeUri() != null
            val dead = configured && !artworkFolderRepository.hasLiveGrant()
            _extra.update { it.copy(artworkFolderGrantDead = dead) }
        }
    }

    val uiState: StateFlow<ArtworkSettingsUiState> = combine(
        sgdbKeyProvider.apiKeyFlow,
        metadataKeyProvider.igdbClientIdFlow,
        metadataKeyProvider.ssUsernameFlow,
        scrapePreferences.preferSteamGridDbHeroesFlow,
        _extra,
    ) { sgdbKey, igdbClientId, ssUsername, preferSgdbHeroes, extra ->
        extra.copy(
            hasApiKey             = !sgdbKey.isNullOrBlank(),
            apiKeyMasked          = if (!sgdbKey.isNullOrBlank()) "••••••" else "",
            hasIgdbCredentials    = !igdbClientId.isNullOrBlank(),
            igdbClientId          = igdbClientId ?: "",
            ssEnabled             = screenScraperApi.isEnabled,
            hasSsCredentials      = !ssUsername.isNullOrBlank(),
            ssUsername            = ssUsername ?: "",
            preferSteamGridDbHeroes = preferSgdbHeroes,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArtworkSettingsUiState())

    init {
        refreshStatus()
        loadScrapePreferences()
    }

    private fun loadScrapePreferences() {
        viewModelScope.launch {
            val opts = scrapePreferences.getOptions()
            _extra.update {
                it.copy(
                    downloadHeroes     = opts.downloadHeroes,
                    downloadLogos      = opts.downloadClearLogos,
                    downloadManuals    = opts.downloadManuals,
                    downloadVideoSnaps = opts.downloadVideoSnaps,
                )
            }
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            _extra.update { it.copy(isLoadingStatus = true) }
            val status = artworkRepository.computeStatus()
            _extra.update { it.copy(status = status, isLoadingStatus = false) }
        }
    }

    fun saveApiKey(key: String) {
        viewModelScope.launch { sgdbKeyProvider.saveKey(key.trim()) }
    }

    fun clearApiKey() {
        viewModelScope.launch { sgdbKeyProvider.clearKey() }
    }

    fun saveIgdbCredentials(clientId: String, clientSecret: String) {
        viewModelScope.launch {
            metadataKeyProvider.saveIgdbCredentials(clientId.trim(), clientSecret.trim())
            _extra.update { it.copy(igdbCredentialStatus = null) }
        }
    }

    fun clearIgdbCredentials() {
        viewModelScope.launch {
            metadataKeyProvider.clearIgdbCredentials()
            _extra.update { it.copy(igdbCredentialStatus = null) }
        }
    }

    fun testIgdbCredentials(clientId: String, clientSecret: String) {
        viewModelScope.launch {
            _extra.update { it.copy(igdbCredentialStatus = "Testing…") }
            val ok = igdbApi.testCredentials(clientId.trim(), clientSecret.trim())
            _extra.update {
                it.copy(igdbCredentialStatus = if (ok) "Valid" else "Invalid — check Client ID and Secret")
            }
        }
    }

    fun dismissCredentialStatus() {
        _extra.update { it.copy(igdbCredentialStatus = null) }
    }

    // ── ScreenScraper account ─────────────────────────────────────────────

    fun saveSsCredentials(username: String, password: String) {
        viewModelScope.launch {
            metadataKeyProvider.saveSsCredentials(username.trim(), password.trim())
            _extra.update { it.copy(ssCredentialStatus = null) }
        }
    }

    fun clearSsCredentials() {
        viewModelScope.launch {
            metadataKeyProvider.clearSsCredentials()
            _extra.update { it.copy(ssCredentialStatus = null) }
        }
    }

    fun testSsCredentials(username: String, password: String) {
        viewModelScope.launch {
            _extra.update { it.copy(ssCredentialStatus = "Testing…") }
            val user = screenScraperApi.fetchUserInfo(username.trim(), password.trim())
            _extra.update {
                it.copy(ssCredentialStatus = if (user != null) {
                    buildString {
                        append("Valid")
                        user.maxThreads?.let { t -> append(" — $t thread${if (t == "1") "" else "s"}") }
                        user.maxRequestsPerDay?.let { q -> append(", $q requests/day") }
                    }
                } else "Invalid — check username and password")
            }
        }
    }

    fun dismissSsCredentialStatus() {
        _extra.update { it.copy(ssCredentialStatus = null) }
    }

    fun requestRescrapeAll() = _extra.update { it.copy(confirmRescrapeAll = true) }
    fun cancelRescrapeAll()  = _extra.update { it.copy(confirmRescrapeAll = false) }

    fun confirmRescrapeAll() {
        _extra.update { it.copy(confirmRescrapeAll = false) }
        runScrape("Re-scraped all games") { onProgress ->
            artworkRepository.reScrapeAllGames(onProgress)
        }
    }

    fun scrapeMissingOnly() {
        runScrape("Scraped missing games") { onProgress ->
            artworkRepository.scrapeMissingOnly(onProgress)
        }
    }

    private fun runScrape(
        label: String,
        block: suspend ((ScrapeProgress) -> Unit) -> ScrapeProgress,
    ) {
        if (_extra.value.isScraping) return
        viewModelScope.launch {
            _extra.update {
                it.copy(
                    isScraping = true, summary = null, scrapeCurrent = 0, scrapeTotal = 0,
                    scrapeSucceeded = 0, scrapeFailed = 0, scrapeTitle = "Starting…",
                    scrapeSource = "", scrapeAsset = "",
                )
            }
            val result = runCatching {
                block { p ->
                    _extra.update {
                        it.copy(
                            scrapeCurrent   = p.current,
                            scrapeTotal     = p.total,
                            scrapeSucceeded = p.succeeded,
                            scrapeFailed    = p.failed,
                            scrapeTitle     = p.title,
                            scrapeSource    = p.scrapeSource,
                            scrapeAsset     = p.scrapeAsset,
                        )
                    }
                }
            }
            val summary = result.getOrNull()?.let { p ->
                "$label: ${p.succeeded} succeeded, ${p.failed} failed of ${p.total}"
            } ?: "Scrape failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
            _extra.update {
                it.copy(isScraping = false, scrapeTitle = "", scrapeSource = "", scrapeAsset = "", summary = summary)
            }
            refreshStatus()
        }
    }

    fun dismissSummary() = _extra.update { it.copy(summary = null) }

    fun clearCache() {
        viewModelScope.launch {
            artworkRepository.clearCache()
            _extra.update { it.copy(diskCacheSizeMb = "0 MB") }
        }
    }

    fun cycleGridStyle() {
        val styles = listOf("Any", "Alternate", "Blurred", "White Logo", "Material")
        val current = _extra.value.preferredGridStyle
        val next = styles[(styles.indexOf(current) + 1) % styles.size]
        _extra.update { it.copy(preferredGridStyle = next) }
    }

    fun setDownloadHeroes(enabled: Boolean) {
        _extra.update { it.copy(downloadHeroes = enabled) }
        viewModelScope.launch { scrapePreferences.setDownloadHeroes(enabled) }
    }

    fun setDownloadLogos(enabled: Boolean) {
        _extra.update { it.copy(downloadLogos = enabled) }
        viewModelScope.launch { scrapePreferences.setDownloadClearLogos(enabled) }
    }

    fun setDownloadManuals(enabled: Boolean) {
        _extra.update { it.copy(downloadManuals = enabled) }
        viewModelScope.launch { scrapePreferences.setDownloadManuals(enabled) }
    }

    fun setDownloadVideoSnaps(enabled: Boolean) {
        _extra.update { it.copy(downloadVideoSnaps = enabled) }
        viewModelScope.launch { scrapePreferences.setDownloadVideoSnaps(enabled) }
    }

    fun setPreferSteamGridDbHeroes(enabled: Boolean) {
        viewModelScope.launch { scrapePreferences.setPreferSteamGridDbHeroes(enabled) }
    }
}
