package com.playfieldportal.feature.appbar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundPlayer
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

// One row in an app's long-press mini menu.
enum class AppMenuAction(val label: String) {
    APP_INFO("App Info"),
    UNINSTALL("Uninstall"),
}

data class AppDrawerUiState(
    val allApps: List<InstalledApp> = emptyList(),
    val visibleApps: List<InstalledApp> = emptyList(),
    val activeFilter: AppFilter = AppFilter.ALL,
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val selectedIndex: Int = 0,
    // True while the user is browsing by touch: the grid cursor is hidden (fingers don't need
    // one) and the auto-scroll-to-selection effect is suppressed so it can't fight the finger.
    // Flips false on the first d-pad action, revealing the cursor at the last touch position.
    val usingTouch: Boolean = false,
    val hasUsageAccess: Boolean = false,
    // Long-press mini menu: the app it targets (null = closed) and the focused row.
    val menuApp: InstalledApp? = null,
    val menuIndex: Int = 0,
    // Uninstall guard rail: the app awaiting the in-app confirmation (null = no dialog).
    val confirmUninstall: InstalledApp? = null,
) {
    // App Info for every app; Uninstall only for non-system apps (guard rail).
    val menuActions: List<AppMenuAction>
        get() = buildList {
            add(AppMenuAction.APP_INFO)
            if (menuApp?.isSystemApp == false) add(AppMenuAction.UNINSTALL)
        }
}

@HiltViewModel
class AppDrawerViewModel @Inject constructor(
    private val appRepository: InstalledAppRepository,
    private val menuSound: MenuSoundPlayer,
    private val discordPresence: com.playfieldportal.core.data.discord.DiscordPresenceController,
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
        if (filter != _uiState.value.activeFilter) menuSound.play(MenuSound.SYSTEM_BROWSE)
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

    /** Touch tap on a grid tile: moves the (hidden) cursor there and enters touch mode. */
    fun onAppTapped(index: Int) {
        _uiState.update { it.copy(selectedIndex = index, usingTouch = true) }
    }

    /** Touch scroll settled: silently park the cursor on the tile nearest the viewport centre so a
     *  later switch to the d-pad starts where the finger left off. No sound — nothing visible moves. */
    fun onTouchBrowse(index: Int) {
        val size = _uiState.value.visibleApps.size
        if (size == 0) return
        _uiState.update { it.copy(selectedIndex = index.coerceIn(0, size - 1), usingTouch = true) }
    }

    fun launchApp(packageName: String) {
        // Fires for both controller SELECT and a touch tap on an app tile.
        menuSound.play(MenuSound.LAUNCH)
        appRepository.launchApp(packageName)
        // Reflect the launch in the opt-in Discord presence (no-op unless Discord is connected and
        // sharing is on). Cleared on return via MainActivity.onResume.
        val label = _uiState.value.allApps.firstOrNull { it.packageName == packageName }?.label
            ?: packageName
        viewModelScope.launch { discordPresence.setCurrentGame(label) }
    }

    fun refresh() {
        loadApps()
    }

    // ── Long-press mini menu ────────────────────────────────────────────────────

    fun openAppMenu(app: InstalledApp) {
        menuSound.play(MenuSound.SELECT)
        _uiState.update { it.copy(menuApp = app, menuIndex = 0) }
    }

    /** Opens the menu for the currently-focused grid app (controller hold). */
    fun openAppMenuForSelected() {
        val app = _uiState.value.visibleApps.getOrNull(_uiState.value.selectedIndex) ?: return
        openAppMenu(app)
    }

    fun closeAppMenu() = _uiState.update { it.copy(menuApp = null) }

    fun onMenuAction(action: AppMenuAction) {
        val app = _uiState.value.menuApp ?: return
        when (action) {
            AppMenuAction.APP_INFO -> {
                appRepository.openAppInfo(app.packageName)
                _uiState.update { it.copy(menuApp = null) }
            }
            // Guard rail: show an in-app confirmation before the system uninstall flow.
            AppMenuAction.UNINSTALL -> _uiState.update { it.copy(menuApp = null, confirmUninstall = app) }
        }
    }

    fun confirmUninstall() {
        val app = _uiState.value.confirmUninstall ?: return
        appRepository.uninstallApp(app.packageName)
        _uiState.update { it.copy(confirmUninstall = null) }
        // The app list refreshes on ON_RESUME when the user returns from the uninstall dialog.
    }

    fun cancelUninstall() = _uiState.update { it.copy(confirmUninstall = null) }

    fun openUsageAccessSettings() {
        appRepository.openUsageAccessSettings()
    }

    fun handleGamepadAction(action: GamepadAction) {
        val state = _uiState.value

        // Uninstall confirmation captures input first (SELECT confirms, anything else cancels).
        state.confirmUninstall?.let {
            when (action) {
                GamepadAction.SELECT -> confirmUninstall()
                else -> cancelUninstall()
            }
            return
        }

        // Mini menu captures input while open.
        state.menuApp?.let {
            val actions = state.menuActions
            when (action) {
                GamepadAction.NAVIGATE_UP   -> _uiState.update { s -> s.copy(menuIndex = (s.menuIndex - 1 + actions.size) % actions.size) }
                GamepadAction.NAVIGATE_DOWN -> _uiState.update { s -> s.copy(menuIndex = (s.menuIndex + 1) % actions.size) }
                GamepadAction.SELECT        -> onMenuAction(actions[state.menuIndex.coerceIn(0, actions.size - 1)])
                // Hold again, or Y, dismisses the menu (BACK closes the whole drawer via XMBViewModel).
                GamepadAction.LONG_PRESS, GamepadAction.BUTTON_Y -> closeAppMenu()
                else -> Unit
            }
            return
        }

        val size  = state.visibleApps.size
        if (size == 0) return
        // Controller input ends touch mode: the cursor appears at the position the last touch
        // browse/tap parked it, and navigation continues from there.
        if (state.usingTouch) _uiState.update { it.copy(usingTouch = false) }
        val cur = state.selectedIndex
        when (action) {
            // Hold a button to open the focused app's mini menu (controller equivalent of long-press).
            GamepadAction.LONG_PRESS -> openAppMenuForSelected()
            GamepadAction.NAVIGATE_LEFT  -> {
                if (cur % GRID_COLUMNS > 0) { _uiState.update { it.copy(selectedIndex = cur - 1) }; menuSound.play(MenuSound.SCROLL) }
            }
            GamepadAction.NAVIGATE_RIGHT -> {
                if (cur % GRID_COLUMNS < GRID_COLUMNS - 1 && cur + 1 < size) {
                    _uiState.update { it.copy(selectedIndex = cur + 1) }; menuSound.play(MenuSound.SCROLL)
                }
            }
            GamepadAction.NAVIGATE_UP    -> {
                val next = cur - GRID_COLUMNS
                if (next >= 0) { _uiState.update { it.copy(selectedIndex = next) }; menuSound.play(MenuSound.SCROLL) }
            }
            GamepadAction.NAVIGATE_DOWN  -> {
                val next = cur + GRID_COLUMNS
                if (next < size) { _uiState.update { it.copy(selectedIndex = next) }; menuSound.play(MenuSound.SCROLL) }
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
