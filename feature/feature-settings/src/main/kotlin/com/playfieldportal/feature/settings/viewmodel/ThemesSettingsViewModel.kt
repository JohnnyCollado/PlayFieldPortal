package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.repository.PfpThemeStore
import com.playfieldportal.core.data.repository.PtfThemeImporter
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

data class ThemesSettingsUiState(
    // Name of the theme applied through PfpThemeStore ("Default" = stock look). The legacy
    // ThemeEntity list is gone from this screen — My Themes IS the theme library now.
    val activeThemeName: String = "Default",
    val isInstalling: Boolean = false,
    val installMessage: String? = null,
    // Custom-theme cascade state (docs/xmb-theme-creator-plan.md): the imported/custom accent
    // that supersedes the preset scheme, and the unified icon tint (null = default white).
    val accentOverrideArgb: Long? = null,
    val iconColorArgb: Long? = null,
    // The user's saved .pfptheme library (imports + Quick Create).
    val savedThemes: List<PfpThemeStore.SavedTheme> = emptyList(),
)

@HiltViewModel
class ThemesSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ptfImporter: PtfThemeImporter,
    private val themeStore: PfpThemeStore,
) : ViewModel() {

    private val _extra = MutableStateFlow(ThemesSettingsUiState())

    val uiState: StateFlow<ThemesSettingsUiState> = combine(
        context.pfpDataStore.data,
        themeStore.themes,
        _extra,
    ) { prefs, saved, extra ->
        extra.copy(
            activeThemeName    = prefs[PfpThemeStore.KEY_APPLIED_THEME_NAME] ?: "Default",
            accentOverrideArgb = prefs[KEY_ACCENT_OVERRIDE],
            iconColorArgb      = prefs[KEY_ICON_COLOR],
            savedThemes        = saved,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemesSettingsUiState())

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

    /** Full reset of the applied theme: wallpaper, colors, icons, and layout back to stock. */
    fun resetTheme() {
        viewModelScope.launch {
            themeStore.resetApplied()
            Timber.i("Theme reset to default")
            _extra.update { it.copy(installMessage = "Theme reset — back to the default look") }
        }
    }

    // ── Saved-theme library (Quick Create + imports) ─────────────────────────

    /** Quick Create: a picked photo becomes a saved+applied theme, accent auto-derived. */
    fun createThemeFromPhoto(uri: Uri) {
        viewModelScope.launch {
            _extra.update { it.copy(isInstalling = true, installMessage = null) }
            val saved = themeStore.createFromImage(uri)
            val message = if (saved != null) {
                themeStore.apply(saved.id)
                "Created \"${saved.name}\"" +
                    if (saved.accentArgb != null) " — color derived from the photo" else ""
            } else "Could not read that image"
            _extra.update { it.copy(isInstalling = false, installMessage = message) }
        }
    }

    fun applySavedTheme(id: String) {
        viewModelScope.launch {
            val ok = themeStore.apply(id)
            if (!ok) _extra.update { it.copy(installMessage = "Could not apply the theme") }
        }
    }

    fun deleteSavedTheme(id: String) {
        viewModelScope.launch { themeStore.delete(id) }
    }

    /** Exports the bundle to shareable cache and opens the system share sheet. */
    fun shareSavedTheme(id: String) {
        viewModelScope.launch {
            val file = themeStore.exportForShare(id)
            if (file == null) {
                _extra.update { it.copy(installMessage = "Could not export the theme") }
                return@launch
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, "Share theme").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Imports a shared `.pfptheme` bundle into the library and applies it. */
    fun importPfpTheme(uri: Uri) {
        viewModelScope.launch {
            _extra.update { it.copy(isInstalling = true, installMessage = null) }
            val saved = themeStore.importBundle(uri)
            val message = if (saved != null) {
                themeStore.apply(saved.id)
                "Imported \"${saved.name}\""
            } else "Not a valid .pfptheme file"
            _extra.update { it.copy(isInstalling = false, installMessage = message) }
        }
    }

    private companion object {
        // Must match XMBViewModel — shared prefs contract for the theme cascade.
        val KEY_ACCENT_OVERRIDE = longPreferencesKey("theme_accent_override")
        val KEY_ICON_COLOR      = longPreferencesKey("theme_icon_color")
    }
}
