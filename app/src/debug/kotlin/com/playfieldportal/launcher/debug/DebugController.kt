package com.playfieldportal.launcher.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class DebugState(
    // Data source
    val useFakeData: Boolean = true,

    // Scenario simulation
    val scenario: DebugScenario = DebugScenario.FULL_LIBRARY,

    // XMB wave control
    val forceWaveMode: ForceWaveMode = ForceWaveMode.NONE,

    // Overlay controls
    val showBootOnNextLaunch: Boolean = false,
    val showTaskTray: Boolean = false,

    // Thermal simulation
    val simulatedThermalStatus: SimulatedThermal = SimulatedThermal.NONE,

    // Performance overlay
    val showPerfOverlay: Boolean = true,
)

enum class DebugScenario(val label: String) {
    FULL_LIBRARY("Full Library — 10 PS2, 6 GBA"),
    FAVORITES_ONLY("Favorites Only"),
    EMPTY_LIBRARY("Empty Library"),
    SINGLE_GAME("Single Game"),
    LARGE_LIBRARY("Large Library — 100 games"),
    MISSING_ROMS("Missing ROMs"),
    NO_ARTWORK("No Artwork"),
}

enum class ForceWaveMode(val label: String) {
    NONE("Auto (normal behavior)"),
    FULL("Force Full 60fps"),
    REDUCED("Force Reduced 15fps"),
    STATIC("Force Static"),
}

enum class SimulatedThermal(val label: String) {
    NONE("None — normal"),
    MODERATE("Moderate — reduce animation"),
    SEVERE("Severe — pause wave"),
    CRITICAL("Critical — static mode"),
}

@Singleton
class DebugController @Inject constructor() {

    private val _state = MutableStateFlow(DebugState())
    val state: StateFlow<DebugState> = _state.asStateFlow()

    val currentState get() = _state.value

    fun setScenario(scenario: DebugScenario) =
        _state.update { it.copy(scenario = scenario) }

    fun setUseFakeData(use: Boolean) =
        _state.update { it.copy(useFakeData = use) }

    fun setForceWaveMode(mode: ForceWaveMode) =
        _state.update { it.copy(forceWaveMode = mode) }

    fun setShowBootOnNextLaunch(show: Boolean) =
        _state.update { it.copy(showBootOnNextLaunch = show) }

    fun setShowTaskTray(show: Boolean) =
        _state.update { it.copy(showTaskTray = show) }

    fun setSimulatedThermal(thermal: SimulatedThermal) =
        _state.update { it.copy(simulatedThermalStatus = thermal) }

    fun setShowPerfOverlay(show: Boolean) =
        _state.update { it.copy(showPerfOverlay = show) }

    fun reset() = _state.update { DebugState() }
}
