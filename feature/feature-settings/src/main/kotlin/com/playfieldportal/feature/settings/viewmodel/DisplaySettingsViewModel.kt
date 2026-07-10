package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.TouchNavButtonMode
import com.playfieldportal.core.domain.model.TouchSensitivity
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
// Must match XMBViewModel.KEY_TOUCH_NAV_BUTTON — both read/write this same pref.
private val KEY_TOUCH_NAV_BUTTON   = stringPreferencesKey("interface_touch_nav_button")
// Must match XMBViewModel.KEY_TOUCH_SENSITIVITY — both read/write this same pref.
private val KEY_TOUCH_SENSITIVITY  = stringPreferencesKey("interface_touch_sensitivity")
internal val KEY_CUSTOM_WALLPAPER  = stringPreferencesKey("display_custom_wallpaper")
// Must match XMBViewModel.KEY_MENU_SOUND_ENABLED — both read/write this same pref.
private val KEY_MENU_SOUND         = booleanPreferencesKey("sound_menu_enabled")
// Scale & Layout — must match XMBViewModel (shared prefs contract). Scale multiplies the
// launcher's density (75%–130%); bar fraction is the crossbar top as a fraction of height.
private val KEY_XMB_SCALE          = floatPreferencesKey("display_xmb_scale")
private val KEY_BAR_TOP_FRACTION   = floatPreferencesKey("display_bar_top_fraction")

const val XMB_SCALE_MIN = 0.75f
const val XMB_SCALE_MAX = 1.30f
const val XMB_SCALE_STEP = 0.05f
// Crossbar position range mirrors XmbLayoutSpecCodec's clamp (5%–45% of screen height).
const val BAR_TOP_MIN = 0.05f
const val BAR_TOP_MAX = 0.45f
const val BAR_TOP_STEP = 0.02f
const val BAR_TOP_DEFAULT = 0.11f

private val ICON_STYLE_LABELS = mapOf(
    "PSP_RECTANGLE" to "PSP Rectangle",
    "CARTRIDGE"     to "Cartridge",
)
private val ICON_STYLE_ORDER = listOf("PSP_RECTANGLE", "CARTRIDGE")

private val SUPPORTED_WALLPAPER_MIME = setOf("image/png", "image/jpeg", "image/webp")

private val TOUCH_NAV_BUTTON_LABELS = mapOf(
    TouchNavButtonMode.AUTO        to "Auto",
    TouchNavButtonMode.ALWAYS_SHOW to "Always Show",
    TouchNavButtonMode.ALWAYS_HIDE to "Always Hide",
)

private val TOUCH_SENSITIVITY_LABELS = mapOf(
    TouchSensitivity.LOW    to "Low",
    TouchSensitivity.NORMAL to "Normal",
    TouchSensitivity.HIGH   to "High",
)

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
    val touchNavButtonMode: TouchNavButtonMode = TouchNavButtonMode.AUTO,
    val touchSensitivity: TouchSensitivity = TouchSensitivity.NORMAL,
    val menuSoundEnabled: Boolean = true,
    val customWallpaperPath: String? = null,
    // Scale & Layout
    val xmbScale: Float = 1f,
    val barTopFraction: Float = BAR_TOP_DEFAULT,
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
            touchNavButtonMode   = TouchNavButtonMode.fromName(prefs[KEY_TOUCH_NAV_BUTTON]),
            touchSensitivity     = TouchSensitivity.fromName(prefs[KEY_TOUCH_SENSITIVITY]),
            menuSoundEnabled     = prefs[KEY_MENU_SOUND]      ?: true,
            customWallpaperPath  = prefs[KEY_CUSTOM_WALLPAPER],
            xmbScale             = (prefs[KEY_XMB_SCALE] ?: 1f).coerceIn(XMB_SCALE_MIN, XMB_SCALE_MAX),
            barTopFraction       = (prefs[KEY_BAR_TOP_FRACTION] ?: BAR_TOP_DEFAULT).coerceIn(BAR_TOP_MIN, BAR_TOP_MAX),
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
    fun setMenuSoundEnabled(v: Boolean)      = save { it[KEY_MENU_SOUND]      = v }

    fun cycleIconStyle() {
        val current = uiState.value.iconStyleName
        val idx  = ICON_STYLE_ORDER.indexOf(current).coerceAtLeast(0)
        val next = ICON_STYLE_ORDER[(idx + 1) % ICON_STYLE_ORDER.size]
        save { it[KEY_ICON_STYLE] = next }
    }

    fun iconStyleLabel(): String = ICON_STYLE_LABELS[uiState.value.iconStyleName] ?: uiState.value.iconStyleName

    fun cycleTouchNavButtonMode() {
        val modes = TouchNavButtonMode.entries
        val next = modes[(modes.indexOf(uiState.value.touchNavButtonMode) + 1) % modes.size]
        save { it[KEY_TOUCH_NAV_BUTTON] = next.name }
    }

    fun touchNavButtonLabel(): String =
        TOUCH_NAV_BUTTON_LABELS[uiState.value.touchNavButtonMode] ?: uiState.value.touchNavButtonMode.name

    fun cycleTouchSensitivity() {
        val levels = TouchSensitivity.entries
        val next = levels[(levels.indexOf(uiState.value.touchSensitivity) + 1) % levels.size]
        save { it[KEY_TOUCH_SENSITIVITY] = next.name }
    }

    fun touchSensitivityLabel(): String =
        TOUCH_SENSITIVITY_LABELS[uiState.value.touchSensitivity] ?: uiState.value.touchSensitivity.name

    // ── Scale & Layout ────────────────────────────────────────────────────────

    /** Steps the whole-launcher scale by [direction] (±1); clamped, applied live. */
    fun adjustXmbScale(direction: Int) {
        val next = (uiState.value.xmbScale + direction * XMB_SCALE_STEP)
            .coerceIn(XMB_SCALE_MIN, XMB_SCALE_MAX)
        save { if (next == 1f) it.remove(KEY_XMB_SCALE) else it[KEY_XMB_SCALE] = next }
    }

    /** Click-to-cycle fallback for touch: wraps from max back to min. */
    fun cycleXmbScale() {
        val cur = uiState.value.xmbScale
        val next = if (cur + XMB_SCALE_STEP > XMB_SCALE_MAX + 0.001f) XMB_SCALE_MIN
                   else cur + XMB_SCALE_STEP
        save { if (next == 1f) it.remove(KEY_XMB_SCALE) else it[KEY_XMB_SCALE] = next }
    }

    /** Steps the crossbar's vertical position by [direction] (±1); clamped, applied live. */
    fun adjustBarTop(direction: Int) {
        val next = (uiState.value.barTopFraction + direction * BAR_TOP_STEP)
            .coerceIn(BAR_TOP_MIN, BAR_TOP_MAX)
        save { if (next == BAR_TOP_DEFAULT) it.remove(KEY_BAR_TOP_FRACTION) else it[KEY_BAR_TOP_FRACTION] = next }
    }

    fun cycleBarTop() {
        val cur = uiState.value.barTopFraction
        val next = if (cur + BAR_TOP_STEP > BAR_TOP_MAX + 0.001f) BAR_TOP_MIN else cur + BAR_TOP_STEP
        save { if (next == BAR_TOP_DEFAULT) it.remove(KEY_BAR_TOP_FRACTION) else it[KEY_BAR_TOP_FRACTION] = next }
    }

    fun resetScaleAndLayout() = save {
        it.remove(KEY_XMB_SCALE)
        it.remove(KEY_BAR_TOP_FRACTION)
    }

    // ── Wallpaper ─────────────────────────────────────────────────────────────

    fun onWallpaperPicked(uri: Uri) {
        viewModelScope.launch {
            val mime = context.contentResolver.getType(uri)
            if (mime != null && mime !in SUPPORTED_WALLPAPER_MIME) {
                _wallpaperMessage.value = "Unsupported format — use PNG, JPG, or WEBP"
                return@launch
            }
            _wallpaperImporting.value = true
            // Write each wallpaper to a UNIQUE file. A fixed filename kept the stored path string
            // identical across replacements, so neither the state (same value) nor Coil's
            // path-keyed image cache ever updated — the old wallpaper stayed on screen. A unique
            // path changes the value (triggering recomposition) and is a fresh Coil key.
            val dir = File(context.filesDir, "wallpaper").apply { mkdirs() }
            val dest = File(dir, "wallpaper_${System.currentTimeMillis()}.jpg")
            val ok = try {
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
                // Remove any previous wallpaper files (including the legacy wallpaper.jpg) so they
                // don't accumulate; keep only the one we just applied.
                dir.listFiles()?.forEach { f ->
                    if (f.absolutePath != dest.absolutePath) runCatching { f.delete() }
                }
                _wallpaperMessage.value = "Wallpaper applied"
            } else {
                runCatching { dest.delete() }
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

    // Hidden-app management now lives in its own screen (AppVisibilityViewModel), reached from
    // Display ▸ Hidden Apps — it lists every app with a per-app show/hide toggle.

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
