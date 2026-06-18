package com.playfieldportal.feature.settings.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.feature.themes.ThemeLoadResult
import com.playfieldportal.feature.themes.ThemeRepository
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

data class ThemeListItem(
    val id: String,
    val name: String,
    val isBuiltIn: Boolean,
)

data class ThemesSettingsUiState(
    val themes: List<ThemeListItem> = emptyList(),
    val activeThemeId: String = "builtin_classic_blue",
    val activeThemeName: String = "Classic PSP Blue",
    val isInstalling: Boolean = false,
    val installMessage: String? = null,
)

@HiltViewModel
class ThemesSettingsViewModel @Inject constructor(
    private val themeRepository: ThemeRepository,
) : ViewModel() {

    private val _extra = MutableStateFlow(ThemesSettingsUiState())

    val uiState: StateFlow<ThemesSettingsUiState> = combine(
        themeRepository.observeAll(),
        _extra,
    ) { themes, extra ->
        val active = themes.firstOrNull { it.id == extra.activeThemeId }
            ?: themes.firstOrNull()
        extra.copy(
            themes          = themes.map { ThemeListItem(it.id, it.name, it.isBuiltIn) },
            activeThemeId   = active?.id   ?: extra.activeThemeId,
            activeThemeName = active?.name ?: extra.activeThemeName,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemesSettingsUiState())

    init {
        // Keep activeThemeId in sync with whatever is active in the DB
        viewModelScope.launch {
            themeRepository.observeActiveTheme().collect { theme ->
                if (theme != null) {
                    _extra.update { it.copy(activeThemeId = theme.id, activeThemeName = theme.name) }
                }
            }
        }
    }

    fun applyTheme(themeId: String) {
        viewModelScope.launch {
            themeRepository.setActiveTheme(themeId)
            Timber.i("Theme applied: $themeId")
        }
    }

    fun installTheme(uri: Uri) {
        viewModelScope.launch {
            _extra.update { it.copy(isInstalling = true, installMessage = null) }
            when (val result = themeRepository.installTheme(uri)) {
                is ThemeLoadResult.Success -> {
                    Timber.i("Theme installed: ${result.themeId}")
                    _extra.update {
                        it.copy(isInstalling = false, installMessage = "Theme installed successfully")
                    }
                }
                is ThemeLoadResult.InvalidFormat -> {
                    Timber.w("Invalid theme format: ${result.reason}")
                    _extra.update {
                        it.copy(isInstalling = false, installMessage = "Invalid theme: ${result.reason}")
                    }
                }
                is ThemeLoadResult.UnsupportedVersion -> {
                    val msg = "Theme requires format v${result.found} (app supports v${result.supported})"
                    Timber.w(msg)
                    _extra.update { it.copy(isInstalling = false, installMessage = msg) }
                }
                is ThemeLoadResult.IoError -> {
                    Timber.w(result.cause, "Theme install IO error")
                    _extra.update {
                        it.copy(isInstalling = false, installMessage = "Could not read file: ${result.cause.message}")
                    }
                }
            }
        }
    }

    fun uninstallTheme(themeId: String) {
        viewModelScope.launch {
            themeRepository.uninstallTheme(themeId)
            Timber.i("Theme uninstalled: $themeId")
        }
    }

    fun dismissMessage() = _extra.update { it.copy(installMessage = null) }
}
