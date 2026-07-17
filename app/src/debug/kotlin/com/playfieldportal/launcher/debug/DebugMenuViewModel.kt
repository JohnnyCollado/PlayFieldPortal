package com.playfieldportal.launcher.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.domain.achievement.ShibaRank
import com.playfieldportal.feature.xmb.preview.PreviewData
import com.playfieldportal.feature.xmb.viewmodel.XMBUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DebugMenuViewModel @Inject constructor(
    private val debugController: DebugController,
    private val debugSeeder: DebugSeeder,
    private val shibaStandingSeeder: ShibaStandingSeeder,
) : ViewModel() {

    val debugState: StateFlow<DebugState> = debugController.state

    // Maps the current scenario to a preview XMBUiState for the right-panel live preview
    val previewState: XMBUiState
        get() = when (debugController.currentState.scenario) {
            DebugScenario.EMPTY_LIBRARY  -> PreviewData.emptyLibraryState
            DebugScenario.MISSING_ROMS   -> PreviewData.defaultState
            DebugScenario.NO_ARTWORK     -> PreviewData.defaultState
            else                         -> PreviewData.defaultState
        }

    fun setUseFakeData(use: Boolean) = debugController.setUseFakeData(use)

    fun setForceWaveMode(mode: ForceWaveMode) = debugController.setForceWaveMode(mode)

    fun setSimulatedThermal(thermal: SimulatedThermal) = debugController.setSimulatedThermal(thermal)

    fun setShowTaskTray(show: Boolean) = debugController.setShowTaskTray(show)

    fun setShowBootOnNextLaunch(show: Boolean) = debugController.setShowBootOnNextLaunch(show)

    fun setShowPerfOverlay(show: Boolean) = debugController.setShowPerfOverlay(show)

    fun reset() = debugController.reset()

    fun reseed(scenario: DebugScenario) {
        viewModelScope.launch {
            debugSeeder.reseed(scenario)
        }
    }

    /** Seeds fake Shiba achievement data landing on [rank] (level/bones/tier pills + recent coins). */
    fun seedShibaStanding(rank: ShibaRank) {
        viewModelScope.launch {
            shibaStandingSeeder.seed(rank)
        }
    }

    /** Removes the seeded Shiba standing block, restoring the real wallet. */
    fun clearShibaStanding() {
        viewModelScope.launch {
            shibaStandingSeeder.clear()
        }
    }
}
