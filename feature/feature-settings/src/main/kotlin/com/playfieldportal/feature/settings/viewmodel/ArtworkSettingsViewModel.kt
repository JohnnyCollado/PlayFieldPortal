package com.playfieldportal.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.feature.artwork.api.SgdbApiKeyProvider
import com.playfieldportal.feature.artwork.api.ArtworkRepository
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
    val isFetching: Boolean = false,
    val fetchProgress: String = "",
    val diskCacheSizeMb: String = "0 MB",
    val preferredGridStyle: String = "Any",
    val downloadHeroes: Boolean = true,
    val downloadLogos: Boolean = true,
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

    fun fetchMissingArtwork() {
        viewModelScope.launch {
            _extra.update { it.copy(isFetching = true, fetchProgress = "Starting…") }
            try {
                artworkRepository.fetchMissingArtwork { done, total, title ->
                    _extra.update { it.copy(fetchProgress = "$done / $total  —  $title") }
                }
            } finally {
                _extra.update { it.copy(isFetching = false, fetchProgress = "") }
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            artworkRepository.clearCache()
            _extra.update { it.copy(diskCacheSizeMb = "0 MB") }
            Timber.i("Artwork cache cleared")
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
