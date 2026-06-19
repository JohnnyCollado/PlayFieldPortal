package com.playfieldportal.feature.appbar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.domain.model.GamepadAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

const val GRID_COLUMNS = 5

enum class AppFilter(val label: String) {
    ALL("All Apps"),
    GAMES("Games"),
    EMULATORS("Emulators"),
    RECENT("Recently Used"),
}

data class AppDrawerUiState(
    val allApps: List<InstalledApp> = emptyList(),
    val visibleApps: List<InstalledApp> = emptyList(),
    val activeFilter: AppFilter = AppFilter.ALL,
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val selectedIndex: Int = 0,
    val hasUsageAccess: Boolean = false,
)

@HiltViewModel
class AppDrawerViewModel @Inject constructor(
    private val appRepository: InstalledAppRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppDrawerUiState())
    val uiState: StateFlow<AppDrawerUiState> = _uiState.asStateFlow()

    init {
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val hasUsageAccess = appRepository.hasUsageAccess()
            val apps = appRepository.getInstalledApps()
            _uiState.update {
                it.copy(
                    allApps = apps,
                    isLoading = false,
                    hasUsageAccess = hasUsageAccess,
                )
            }
            applyFilter()
        }
    }

    fun setFilter(filter: AppFilter) {
        _uiState.update { it.copy(activeFilter = filter, selectedIndex = 0) }
        applyFilter()
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query, selectedIndex = 0) }
        applyFilter()
    }

    fun onAppSelected(index: Int) {
        _uiState.update { it.copy(selectedIndex = index) }
    }

    fun launchApp(packageName: String) {
        appRepository.launchApp(packageName)
    }

    fun refresh() {
        loadApps()
    }

    fun openUsageAccessSettings() {
        appRepository.openUsageAccessSettings()
    }

    fun handleGamepadAction(action: GamepadAction) {
        val state = _uiState.value
        val size  = state.visibleApps.size
        if (size == 0) return
        val cur = state.selectedIndex
        when (action) {
            GamepadAction.NAVIGATE_LEFT  -> {
                if (cur % GRID_COLUMNS > 0) _uiState.update { it.copy(selectedIndex = cur - 1) }
            }
            GamepadAction.NAVIGATE_RIGHT -> {
                if (cur % GRID_COLUMNS < GRID_COLUMNS - 1 && cur + 1 < size)
                    _uiState.update { it.copy(selectedIndex = cur + 1) }
            }
            GamepadAction.NAVIGATE_UP    -> {
                val next = cur - GRID_COLUMNS
                if (next >= 0) _uiState.update { it.copy(selectedIndex = next) }
            }
            GamepadAction.NAVIGATE_DOWN  -> {
                val next = cur + GRID_COLUMNS
                if (next < size) _uiState.update { it.copy(selectedIndex = next) }
            }
            GamepadAction.SELECT -> {
                val app = state.visibleApps.getOrNull(cur)
                if (app != null) launchApp(app.packageName)
            }
            // L1 / R1 — cycle through filter tabs (App Drawer only)
            GamepadAction.PREV_CATEGORY -> {
                val filters = AppFilter.values()
                val idx = filters.indexOf(state.activeFilter)
                if (idx > 0) setFilter(filters[idx - 1])
            }
            GamepadAction.NEXT_CATEGORY -> {
                val filters = AppFilter.values()
                val idx = filters.indexOf(state.activeFilter)
                if (idx < filters.size - 1) setFilter(filters[idx + 1])
            }
            else -> Unit
        }
    }

    private fun applyFilter() {
        val state = _uiState.value
        val query = state.searchQuery.trim().lowercase()

        val filtered = state.allApps
            .filter { app ->
                when (state.activeFilter) {
                    AppFilter.ALL       -> true
                    AppFilter.GAMES     -> app.isGame
                    AppFilter.EMULATORS -> app.isEmulator
                    AppFilter.RECENT    -> app.lastUsedAt > 0L
                }
            }
            .filter { app ->
                query.isEmpty() || app.label.lowercase().contains(query)
            }
            .let { apps ->
                if (state.activeFilter == AppFilter.RECENT) {
                    apps.sortedByDescending { it.lastUsedAt }
                } else {
                    apps
                }
            }

        _uiState.update { it.copy(visibleApps = filtered) }
    }
}
