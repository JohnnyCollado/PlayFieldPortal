package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.repository.PtfThemeImporter
import com.playfieldportal.feature.themes.ThemeLoadResult
import com.playfieldportal.feature.themes.ThemeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    // Custom-theme cascade state (docs/xmb-theme-creator-plan.md): the imported/custom accent
    // that supersedes the preset scheme, and the unified icon tint (null = default white).
    val accentOverrideArgb: Long? = null,
    val iconColorArgb: Long? = null,
)

@HiltViewModel
class ThemesSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val themeRepository: ThemeRepository,
    private val ptfImporter: PtfThemeImporter,
) : ViewModel() {

    private val _extra = MutableStateFlow(ThemesSettingsUiState())

    val uiState: StateFlow<ThemesSettingsUiState> = combine(
        themeRepository.observeAll(),
        context.pfpDataStore.data,
        _extra,
    ) { themes, prefs, extra ->
        val active = themes.firstOrNull { it.id == extra.activeThemeId }
            ?: themes.firstOrNull()
        extra.copy(
            themes             = themes.map { ThemeListItem(it.id, it.name, it.isBuiltIn) },
            activeThemeId      = active?.id   ?: extra.activeThemeId,
            activeThemeName    = active?.name ?: extra.activeThemeName,
            accentOverrideArgb = prefs[KEY_ACCENT_OVERRIDE],
            iconColorArgb      = prefs[KEY_ICON_COLOR],
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

    // ── Custom theme cascade ─────────────────────────────────────────────────

    /** Imports a user-picked official PSP theme (.ptf): wallpaper + derived accent. */
    fun importPtfTheme(uri: Uri) {
        viewModelScope.launch {
            _extra.update { it.copy(isInstalling = true, installMessage = null) }
            val message = when (val result = ptfImporter.import(uri)) {
                is PtfThemeImporter.Result.Success ->
                    "Imported \"${result.themeName}\" — wallpaper applied" +
                        if (result.accentArgb != null) " with its color" else ""
                PtfThemeImporter.Result.CxmbNotSupported ->
                    "CXMB (.ctf) themes aren't supported — only official .ptf themes"
                is PtfThemeImporter.Result.Failed -> result.reason
            }
            Timber.i("PTF import: %s", message)
            _extra.update { it.copy(isInstalling = false, installMessage = message) }
        }
    }

    /** Sets the unified icon tint; null restores the default (white / icon art's own color). */
    fun setIconColor(argb: Long?) {
        viewModelScope.launch {
            context.pfpDataStore.edit { prefs ->
                if (argb != null) prefs[KEY_ICON_COLOR] = argb else prefs.remove(KEY_ICON_COLOR)
            }
        }
    }

    /** Clears an imported/custom accent so the preset color scheme applies again. */
    fun clearAccentOverride() {
        viewModelScope.launch { context.pfpDataStore.edit { it.remove(KEY_ACCENT_OVERRIDE) } }
    }

    private companion object {
        // Must match XMBViewModel — shared prefs contract for the theme cascade.
        val KEY_ACCENT_OVERRIDE = longPreferencesKey("theme_accent_override")
        val KEY_ICON_COLOR      = longPreferencesKey("theme_icon_color")
    }
}
