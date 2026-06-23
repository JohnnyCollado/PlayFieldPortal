package com.playfieldportal.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.repository.ControllerLayoutRepository
import com.playfieldportal.core.data.repository.ControllerMappingRepository
import com.playfieldportal.core.domain.model.ConfirmBackLayout
import com.playfieldportal.core.domain.model.ControllerDisplayType
import com.playfieldportal.core.domain.model.ControllerLayoutPrefs
import com.playfieldportal.core.domain.model.XYLayout
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ControllerSettingsUiState(
    val layoutPrefs: ControllerLayoutPrefs = ControllerLayoutPrefs(),
)

@HiltViewModel
class ControllerSettingsViewModel @Inject constructor(
    private val mappingRepository: ControllerMappingRepository,
    private val layoutRepository: ControllerLayoutRepository,
) : ViewModel() {

    val uiState: StateFlow<ControllerSettingsUiState> = layoutRepository.prefs
        .map { layoutPrefs ->
            ControllerSettingsUiState(layoutPrefs = layoutPrefs)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ControllerSettingsUiState())

    fun cycleConfirmBackLayout() {
        val next = when (uiState.value.layoutPrefs.confirmBackLayout) {
            ConfirmBackLayout.STANDARD -> ConfirmBackLayout.REVERSED
            ConfirmBackLayout.REVERSED -> ConfirmBackLayout.STANDARD
        }
        viewModelScope.launch { layoutRepository.setConfirmBackLayout(next) }
    }

    fun cycleXYLayout() {
        val next = when (uiState.value.layoutPrefs.xyLayout) {
            XYLayout.STANDARD -> XYLayout.SWAPPED
            XYLayout.SWAPPED  -> XYLayout.STANDARD
        }
        viewModelScope.launch { layoutRepository.setXYLayout(next) }
    }

    fun cycleDisplayType() {
        val types = listOf(
            ControllerDisplayType.XBOX,
            ControllerDisplayType.NINTENDO,
            ControllerDisplayType.PLAYSTATION,
        )
        val current = uiState.value.layoutPrefs.displayType
        val currentIndex = types.indexOf(current).takeIf { it >= 0 } ?: 0
        val next = types[(currentIndex + 1) % types.size]
        viewModelScope.launch { layoutRepository.setDisplayType(next) }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            mappingRepository.resetToDefaults()
            layoutRepository.resetAllPrefs()
        }
    }
}
