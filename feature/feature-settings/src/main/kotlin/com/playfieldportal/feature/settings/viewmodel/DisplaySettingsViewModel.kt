package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.ui.wave.WaveRenderMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private val KEY_WAVE_MODE          = stringPreferencesKey("display_wave_mode")
private val KEY_AUTO_REDUCE        = booleanPreferencesKey("display_auto_reduce")
private val KEY_SHOW_BOOT          = booleanPreferencesKey("display_show_boot")
private val KEY_BOOT_ON_RESUME     = booleanPreferencesKey("display_boot_on_resume")
private val KEY_THERMAL_AWARE      = booleanPreferencesKey("display_thermal_aware")
private val KEY_RESPECT_BATTERY    = booleanPreferencesKey("display_battery_saver")
private val KEY_ICON_STYLE         = stringPreferencesKey("display_icon_style")

private val ICON_STYLE_LABELS = mapOf(
    "PSP_RECTANGLE" to "PSP Rectangle",
    "CARTRIDGE"     to "Cartridge",
)
private val ICON_STYLE_ORDER = listOf("PSP_RECTANGLE", "CARTRIDGE")

data class DisplaySettingsUiState(
    val waveModeName: String = WaveRenderMode.FULL.name,
    val autoReduceOnIdle: Boolean = true,
    val showBootSequence: Boolean = true,
    val showBootOnResume: Boolean = false,
    val thermalThrottleAware: Boolean = true,
    val respectBatterySaver: Boolean = true,
    // Raw enum name — mapped to a display label in the UI
    val iconStyleName: String = "PSP_RECTANGLE",
)

@HiltViewModel
class DisplaySettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val uiState: StateFlow<DisplaySettingsUiState> = context.pfpDataStore.data
        .map { prefs ->
            DisplaySettingsUiState(
                waveModeName       = prefs[KEY_WAVE_MODE]       ?: WaveRenderMode.FULL.name,
                autoReduceOnIdle   = prefs[KEY_AUTO_REDUCE]     ?: true,
                showBootSequence   = prefs[KEY_SHOW_BOOT]       ?: true,
                showBootOnResume   = prefs[KEY_BOOT_ON_RESUME]  ?: false,
                thermalThrottleAware = prefs[KEY_THERMAL_AWARE] ?: true,
                respectBatterySaver  = prefs[KEY_RESPECT_BATTERY] ?: true,
                iconStyleName      = prefs[KEY_ICON_STYLE]      ?: "PSP_RECTANGLE",
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DisplaySettingsUiState())

    fun cycleWaveMode() {
        val modes = WaveRenderMode.values()
        val current = WaveRenderMode.valueOf(uiState.value.waveModeName)
        val next = modes[(modes.indexOf(current) + 1) % modes.size]
        save { it[KEY_WAVE_MODE] = next.name }
    }

    fun setAutoReduceOnIdle(v: Boolean)      = save { it[KEY_AUTO_REDUCE]     = v }
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

    private fun save(block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        viewModelScope.launch { context.pfpDataStore.edit { block(it) } }
    }
}
