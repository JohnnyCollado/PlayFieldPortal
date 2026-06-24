package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.ui.wave.WaveStyle
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
import java.io.File
import javax.inject.Inject

private val KEY_WAVE_STYLE         = stringPreferencesKey("display_wave_style")
private val KEY_SHOW_BOOT          = booleanPreferencesKey("display_show_boot")
private val KEY_BOOT_ON_RESUME     = booleanPreferencesKey("display_boot_on_resume")
private val KEY_THERMAL_AWARE      = booleanPreferencesKey("display_thermal_aware")
private val KEY_RESPECT_BATTERY    = booleanPreferencesKey("display_battery_saver")
private val KEY_ICON_STYLE         = stringPreferencesKey("display_icon_style")
internal val KEY_CUSTOM_WALLPAPER  = stringPreferencesKey("display_custom_wallpaper")

private val ICON_STYLE_LABELS = mapOf(
    "PSP_RECTANGLE" to "PSP Rectangle",
    "CARTRIDGE"     to "Cartridge",
)
private val ICON_STYLE_ORDER = listOf("PSP_RECTANGLE", "CARTRIDGE")

private val SUPPORTED_WALLPAPER_MIME = setOf("image/png", "image/jpeg", "image/webp")

private val WAVE_STYLE_LABELS = mapOf(
    WaveStyle.ANIMATED       to "Animated",
    WaveStyle.REDUCED        to "Reduced",
    WaveStyle.STATIC         to "Static",
    WaveStyle.REDUCED_STATIC to "Reduced + Static",
)

data class DisplaySettingsUiState(
    val waveStyle: WaveStyle = WaveStyle.ANIMATED,
    val showBootSequence: Boolean = true,
    val showBootOnResume: Boolean = false,
    val thermalThrottleAware: Boolean = true,
    val respectBatterySaver: Boolean = true,
    // Raw enum name — mapped to a display label in the UI
    val iconStyleName: String = "PSP_RECTANGLE",
    val customWallpaperPath: String? = null,
    val wallpaperMessage: String? = null,
    val wallpaperImporting: Boolean = false,
    val wallpaperPreviewVisible: Boolean = false,
) {
    val waveStyleLabel: String get() = WAVE_STYLE_LABELS[waveStyle] ?: waveStyle.name
}

@HiltViewModel
class DisplaySettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _wallpaperMessage  = MutableStateFlow<String?>(null)
    private val _wallpaperImporting = MutableStateFlow(false)
    private val _wallpaperPreviewVisible = MutableStateFlow(false)

    val uiState: StateFlow<DisplaySettingsUiState> = combine(
        context.pfpDataStore.data,
        _wallpaperMessage,
        _wallpaperImporting,
        _wallpaperPreviewVisible,
    ) { prefs, msg, importing, previewVisible ->
        DisplaySettingsUiState(
            waveStyle            = runCatching {
                WaveStyle.valueOf(prefs[KEY_WAVE_STYLE] ?: WaveStyle.ANIMATED.name)
            }.getOrDefault(WaveStyle.ANIMATED),
            showBootSequence     = prefs[KEY_SHOW_BOOT]       ?: true,
            showBootOnResume     = prefs[KEY_BOOT_ON_RESUME]  ?: false,
            thermalThrottleAware = prefs[KEY_THERMAL_AWARE]   ?: true,
            respectBatterySaver  = prefs[KEY_RESPECT_BATTERY] ?: true,
            iconStyleName        = prefs[KEY_ICON_STYLE]      ?: "PSP_RECTANGLE",
            customWallpaperPath  = prefs[KEY_CUSTOM_WALLPAPER],
            wallpaperMessage     = msg,
            wallpaperImporting   = importing,
            wallpaperPreviewVisible = previewVisible,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DisplaySettingsUiState())

    fun cycleWaveStyle() {
        val styles = WaveStyle.values()
        val next = styles[(styles.indexOf(uiState.value.waveStyle) + 1) % styles.size]
        save { it[KEY_WAVE_STYLE] = next.name }
    }

    fun setShowBootSequence(v: Boolean)      = save { it[KEY_SHOW_BOOT]       = v }
    fun setShowBootOnResume(v: Boolean)      = save { it[KEY_BOOT_ON_RESUME]  = v }
    fun setThermalThrottleAware(v: Boolean)  = save { it[KEY_THERMAL_AWARE]   = v }
    fun setRespectBatterySaver(v: Boolean)   = save { it[KEY_RESPECT_BATTERY] = v }

    fun cycleIconStyle() {
        val current = uiState.value.iconStyleName
        val idx  = ICON_STYLE_ORDER.indexOf(current).coerceAtLeast(0)
        val next = ICON_STYLE_ORDER[(idx + 1) % ICON_STYLE_ORDER.size]
        save { it[KEY_ICON_STYLE] = next }
    }

    fun iconStyleLabel(): String = ICON_STYLE_LABELS[uiState.value.iconStyleName] ?: uiState.value.iconStyleName

    // ── Wallpaper ─────────────────────────────────────────────────────────────

    fun onWallpaperPicked(uri: Uri) {
        viewModelScope.launch {
            val mime = context.contentResolver.getType(uri)
            if (mime != null && mime !in SUPPORTED_WALLPAPER_MIME) {
                _wallpaperMessage.value = "Unsupported format — use PNG, JPG, or WEBP"
                return@launch
            }
            _wallpaperImporting.value = true
            val dest = File(context.filesDir, "wallpaper/wallpaper.jpg")
            val ok = try {
                dest.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
                true
            } catch (e: Exception) {
                Timber.w(e, "Failed to import wallpaper")
                false
            }
            _wallpaperImporting.value = false
            if (ok) {
                save { it[KEY_CUSTOM_WALLPAPER] = dest.absolutePath }
                _wallpaperMessage.value = "Wallpaper applied"
            } else {
                _wallpaperMessage.value = "Failed to import wallpaper — please try again"
            }
        }
    }

    fun clearWallpaper() {
        viewModelScope.launch {
            save { it.remove(KEY_CUSTOM_WALLPAPER) }
            _wallpaperMessage.value = "Wallpaper reset to default"
        }
    }

    fun dismissWallpaperMessage() {
        _wallpaperMessage.value = null
    }

    fun showWallpaperPreview() {
        if (uiState.value.customWallpaperPath != null) {
            _wallpaperPreviewVisible.value = true
        } else {
            _wallpaperMessage.value = "No wallpaper selected yet"
        }
    }

    fun hideWallpaperPreview() {
        _wallpaperPreviewVisible.value = false
    }

    private fun save(block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        viewModelScope.launch { context.pfpDataStore.edit { block(it) } }
    }
}
