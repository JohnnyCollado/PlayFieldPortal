package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.XmbColorScheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject

private val KEY_COLOR_SCHEME = stringPreferencesKey("display_color_scheme")

data class ColorSchemeUiState(
    val activeScheme: XmbColorScheme = XmbColorScheme.CLASSIC_BLUE,
    // Current month (1-12) so the UI can resolve the ORIGINAL scheme's preview swatch.
    val month: Int = LocalDate.now().monthValue,
)

@HiltViewModel
class ColorSchemeSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val uiState: StateFlow<ColorSchemeUiState> = context.pfpDataStore.data
        .map { prefs ->
            val scheme = runCatching {
                XmbColorScheme.valueOf(prefs[KEY_COLOR_SCHEME] ?: XmbColorScheme.CLASSIC_BLUE.name)
            }.getOrDefault(XmbColorScheme.CLASSIC_BLUE)
            ColorSchemeUiState(activeScheme = scheme, month = LocalDate.now().monthValue)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ColorSchemeUiState())

    fun selectScheme(scheme: XmbColorScheme) {
        viewModelScope.launch {
            context.pfpDataStore.edit { it[KEY_COLOR_SCHEME] = scheme.name }
            Timber.i("Color scheme set: $scheme")
        }
    }
}
