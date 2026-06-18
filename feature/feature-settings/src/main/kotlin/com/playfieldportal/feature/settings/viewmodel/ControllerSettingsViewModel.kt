package com.playfieldportal.feature.settings.viewmodel

import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.feature.xmb.gamepad.ControllerMappingRepository
import com.playfieldportal.feature.xmb.gamepad.GamepadAction
import com.playfieldportal.feature.xmb.gamepad.GamepadMappings
import com.playfieldportal.feature.xmb.gamepad.displayLabel
import com.playfieldportal.feature.xmb.gamepad.keycodeDisplayName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MappingRow(
    val action: GamepadAction,
    val actionLabel: String,
    val keyCode: Int,
    val keyLabel: String,
    val isRemapping: Boolean = false,
)

data class ControllerSettingsUiState(
    val mappings: List<MappingRow> = emptyList(),
    val remappingAction: GamepadAction? = null,   // non-null while listening for a new key
    val repeatDelayMs: Long = 400L,
    val repeatRateMs: Long = 120L,
)

@HiltViewModel
class ControllerSettingsViewModel @Inject constructor(
    private val mappingRepository: ControllerMappingRepository,
) : ViewModel() {

    private val _extra = MutableStateFlow(ControllerSettingsUiState())

    val uiState: StateFlow<ControllerSettingsUiState> = combine(
        mappingRepository.mappings,
        _extra,
    ) { gameMappings, extra ->
        extra.copy(
            mappings = gameMappings.bindings
                .sortedBy { it.action.ordinal }
                .map { binding ->
                    MappingRow(
                        action       = binding.action,
                        actionLabel  = binding.action.displayLabel(),
                        keyCode      = binding.keyCode,
                        keyLabel     = binding.keyCode.keycodeDisplayName(),
                        isRemapping  = extra.remappingAction == binding.action,
                    )
                },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ControllerSettingsUiState())

    // Enter remapping mode for the given action — next key press binds to it
    fun startRemap(action: GamepadAction) {
        _extra.update { it.copy(remappingAction = action) }
    }

    fun cancelRemap() {
        _extra.update { it.copy(remappingAction = null) }
    }

    // Called from the settings screen when a key is pressed during remapping
    fun onKeyPressedDuringRemap(keyCode: Int) {
        val action = _extra.value.remappingAction ?: return
        _extra.update { it.copy(remappingAction = null) }
        viewModelScope.launch {
            mappingRepository.remap(action, keyCode)
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            mappingRepository.resetToDefaults()
        }
    }
}
