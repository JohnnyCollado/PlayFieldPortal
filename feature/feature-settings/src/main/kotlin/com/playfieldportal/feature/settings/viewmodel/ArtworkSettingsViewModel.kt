package com.playfieldportal.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.feature.artwork.api.ArtworkRepository
import com.playfieldportal.feature.artwork.api.ArtworkStatus
import com.playfieldportal.feature.artwork.api.ScrapeProgress
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
    val diskCacheSizeMb: String = "0 MB",
    val preferredGridStyle: String = "Any",
    val downloadHeroes: Boolean = true,
    val downloadLogos: Boolean = true,
    // ── Artwork status (file-aware) ───────────────────────────────────────
    val status: ArtworkStatus = ArtworkStatus(),
    val isLoadingStatus: Boolean = false,
    // ── Scraping ──────────────────────────────────────────────────────────
    val isScraping: Boolean = false,
    val scrapeCurrent: Int = 0,
    val scrapeTotal: Int = 0,
    val scrapeSucceeded: Int = 0,
    val scrapeFailed: Int = 0,
    val scrapeTitle: String = "",
    val summary: String? = null,
    // ── Confirmation ──────────────────────────────────────────────────────
    val confirmRescrapeAll: Boolean = false,
)

@HiltViewModel
class ArtworkSettingsViewModel @Inject constructor(
    private val apiKeyProvider: SgdbApiKeyProvider,
    private val artworkRepository: ArtworkRepository,
) : ViewModel() {

    private val _extra = MutableStateFlow(ArtworkSettingsUiState())

    val uiState: StateFlow<ArtworkSettingsUiState> = combine(
        apiKeyProvider.apiKeyFlow,
        _extra,
    ) { key, extra ->
        extra.copy(
            hasApiKey    = !key.isNullOrBlank(),
            apiKeyMasked = if (!key.isNullOrBlank()) "••••••" else "",
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArtworkSettingsUiState())

    init {
        refreshStatus()
    }

    // Recomputes real, file-aware artwork status. Called on open and after every operation so
    // the UI never relies on stale counts.
    fun refreshStatus() {
        viewModelScope.launch {
            _extra.update { it.copy(isLoadingStatus = true) }
            val status = artworkRepository.computeStatus()
            _extra.update { it.copy(status = status, isLoadingStatus = false) }
        }
    }

    fun saveApiKey(key: String) {
        viewModelScope.launch {
            apiKeyProvider.saveKey(key.trim())
            Timber.i("SteamGridDB API key saved")
        }
    }

    fun clearApiKey() {
        viewModelScope.launch {
            apiKeyProvider.clearKey()
            Timber.i("SteamGridDB API key cleared")
        }
    }

    // ── Scraping ────────────────────────────────────────────────────────────────

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
                it.copy(isScraping = true, summary = null, scrapeCurrent = 0, scrapeTotal = 0,
                    scrapeSucceeded = 0, scrapeFailed = 0, scrapeTitle = "Starting…")
            }
            val result = runCatching {
                block { p ->
                    _extra.update {
                        it.copy(scrapeCurrent = p.current, scrapeTotal = p.total,
                            scrapeSucceeded = p.succeeded, scrapeFailed = p.failed, scrapeTitle = p.title)
                    }
                }
            }
            val summary = result.getOrNull()?.let { p ->
                "$label: ${p.succeeded} succeeded, ${p.failed} failed of ${p.total}"
            } ?: "Scrape failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
            _extra.update { it.copy(isScraping = false, scrapeTitle = "", summary = summary) }
            refreshStatus()   // UI updates immediately, no restart needed
        }
    }

    fun dismissSummary() = _extra.update { it.copy(summary = null) }

    // Lightweight: clears only the in-memory/disk image cache so images re-download on next
    // view. Does not touch DB references or scraped files (that's part of Re-Scrape All).
    fun clearCache() {
        viewModelScope.launch {
            artworkRepository.clearCache()
            _extra.update { it.copy(diskCacheSizeMb = "0 MB") }
            Timber.i("Artwork image cache cleared")
        }
    }

    fun cycleGridStyle() {
        val styles = listOf("Any", "Alternate", "Blurred", "White Logo", "Material")
        val current = _extra.value.preferredGridStyle
        val next = styles[(styles.indexOf(current) + 1) % styles.size]
        _extra.update { it.copy(preferredGridStyle = next) }
    }

    fun setDownloadHeroes(enabled: Boolean) = _extra.update { it.copy(downloadHeroes = enabled) }
    fun setDownloadLogos(enabled: Boolean)  = _extra.update { it.copy(downloadLogos = enabled) }
}
