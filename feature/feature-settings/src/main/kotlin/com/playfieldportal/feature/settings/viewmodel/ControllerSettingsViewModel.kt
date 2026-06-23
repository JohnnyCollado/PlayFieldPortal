package com.playfieldportal.feature.settings.viewmodel

import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.repository.ControllerMappingRepository
import com.playfieldportal.core.data.repository.RemapCoordinator
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.GamepadMappings
import com.playfieldportal.core.domain.model.displayLabel
import com.playfieldportal.core.domain.model.keycodeDisplayName
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
    private val remapCoordinator: RemapCoordinator,
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

    // Enter remapping mode for the given action — next controller button press binds to it.
    // Physical Back key (KEYCODE_BACK) cancels without assigning; all other keys assign.
    fun startRemap(action: GamepadAction) {
        _extra.update { it.copy(remappingAction = action) }
        remapCoordinator.captureNextKey = { keyCode ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                _extra.update { it.copy(remappingAction = null) }
            } else {
                onKeyPressedDuringRemap(keyCode)
            }
        }
    }

    fun cancelRemap() {
        remapCoordinator.captureNextKey = null
        _extra.update { it.copy(remappingAction = null) }
    }

    // Called when a raw keyCode is captured during remapping (via RemapCoordinator).
    fun onKeyPressedDuringRemap(keyCode: Int) {
        val action = _extra.value.remappingAction ?: return
        _extra.update { it.copy(remappingAction = null) }
        viewModelScope.launch {
            mappingRepository.remap(action, keyCode)
        }
    }

    override fun onCleared() {
        super.onCleared()
        remapCoordinator.captureNextKey = null
    }

    // Reverse-lookup: which raw keyCode is currently bound to the given action?
    // Used by the remap interceptor to convert an incoming GamepadAction back to a keyCode.
    fun keycodeForAction(action: GamepadAction): Int? =
        uiState.value.mappings.firstOrNull { it.action == action }?.keyCode

    fun resetToDefaults() {
        viewModelScope.launch {
            mappingRepository.resetToDefaults()
        }
    }
}
