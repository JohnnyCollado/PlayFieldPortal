package com.playfieldportal.feature.appbar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.PspMenuCue
import com.playfieldportal.core.ui.components.PspMenuNav
import com.playfieldportal.core.ui.components.PspMenuOutcome
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundPlayer
import com.playfieldportal.core.ui.sound.MenuSoundSink
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

const val GRID_COLUMNS = 6

// The Android Memory Card's platform id, and the sentinel platform for rows that only back an
// app's artwork/favorites/collections without placing it in the library (mirrors XMBViewModel).
private const val ANDROID_PLATFORM_ID = "android"
private const val APP_SHORTCUT_PLATFORM_ID = "app_shortcut"

enum class AppFilter(val label: String) {
    ALL("All Apps"),
    GAMES("Games"),
    EMULATORS("Emulators"),
    RECENT("Recently Used"),
}

/**
 * A menu row the drawer cannot run itself: the XMB owns App Detail, the Card picker and the Favorite
 * toggle (with its notification), so the screen forwards these up to it.
 */
sealed interface AppDrawerEvent {
    data class EditDetails(val packageName: String) : AppDrawerEvent
    data class AddToCard(val packageName: String, val label: String) : AppDrawerEvent
    data class ToggleFavorite(val packageName: String, val label: String) : AppDrawerEvent
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
    // Long-press menu: the app it targets (null = closed) and its rows. Set together, and only once
    // Favorite / Mark as Game are known, so the menu never changes under the cursor.
    val menuApp: InstalledApp? = null,
    val menuRows: List<AppMenuEntry> = emptyList(),
    val menuIndex: Int = 0,
    // Uninstall guard rail: the app awaiting the in-app confirmation (null = no dialog).
    val confirmUninstall: InstalledApp? = null,
    /** Per-filter app counts (unfiltered by search query) for the category rail. */
    val filterCounts: Map<AppFilter, Int> = emptyMap(),
)

@HiltViewModel
class AppDrawerViewModel @Inject constructor(
    private val appRepository: InstalledAppRepository,
    private val appCategoryRepository: AppCategoryRepository,
    private val menuSound: MenuSoundPlayer,
    private val discordPresence: com.playfieldportal.core.data.discord.DiscordPresenceController,
    private val gameRepository: com.playfieldportal.core.domain.repository.GameRepository,
    private val memoryCardRepository: com.playfieldportal.core.data.repository.MemoryCardRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppDrawerUiState())
    val uiState: StateFlow<AppDrawerUiState> = _uiState.asStateFlow()

    private val _events = Channel<AppDrawerEvent>(Channel.BUFFERED)
    val events: Flow<AppDrawerEvent> = _events.receiveAsFlow()

    // Resolving the open menu's values; cancelled when the menu closes so a late result cannot reopen it.
    private var menuJob: Job? = null

    init {
        loadApps()
        // The drawer used to reload only here and on ON_RESUME, and that observer lives in the
        // screen's DisposableEffect — so it is only registered while the drawer is composed. This
        // ViewModel is Activity-scoped (hiltViewModel() with no nav entry), so it outlives the
        // drawer closing: installing an APK with the drawer shut and reopening it showed the list
        // built at construction. Reacting to the catalog itself fixes that whether or not anything
        // is on screen. drop(1) skips changes()' replayed opening value, which loadApps() above
        // has already covered.
        viewModelScope.launch {
            appCategoryRepository.changes().drop(1).collect { loadApps() }
        }
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
        // Fires for both controller SELECT and a touch tap on an app tile. Deliberately silent:
        // opening an app has no cue — the app taking the screen is the feedback. Only a game boot
        // is scored, and that is GameBoot's presentation, not a menu sound.
        appRepository.launchApp(packageName)
        // Reflect the launch in the opt-in Discord presence (no-op unless Discord is connected and
        // sharing is on). Cleared on return via MainActivity.onResume.
        val label = _uiState.value.allApps.firstOrNull { it.packageName == packageName }?.label
            ?: packageName
        viewModelScope.launch { discordPresence.setCurrentGame(label) }
    }

    /**
     * Reload on ON_RESUME. Not redundant with the catalog subscription above: `lastUsedAt` comes
     * from UsageStatsManager, so returning from an app reorders "Recently Used" with no package
     * event to react to.
     */
    fun refresh() {
        loadApps()
    }

    // ── Long-press mini menu ────────────────────────────────────────────────────

    fun openAppMenu(app: InstalledApp) {
        menuJob?.cancel()
        // Favorite and Mark as Game come from the app's shortcut row. The menu is published only once
        // they are read, so it opens with the right values instead of flipping a label under the cursor.
        menuJob = viewModelScope.launch {
            val entry = gameRepository.getAppEntry(app.packageName)
            val isGame = entry != null &&
                entry.platformId == ANDROID_PLATFORM_ID &&
                entry.contentType == com.playfieldportal.core.domain.model.GameContentType.GAME
            val rows = appDrawerMenuItems(
                isFavorite = entry?.isFavorite == true,
                isGame = isGame,
                isSystemApp = app.isSystemApp,
            )
            menuSound.play(MenuSound.SELECT)
            _uiState.update { it.copy(menuApp = app, menuRows = rows, menuIndex = 0) }
        }
    }

    /** Opens the menu for the currently-focused grid app (controller hold). */
    fun openAppMenuForSelected() {
        val app = _uiState.value.visibleApps.getOrNull(_uiState.value.selectedIndex) ?: return
        openAppMenu(app)
    }

    fun closeAppMenu() {
        menuJob?.cancel()
        _uiState.update { it.copy(menuApp = null) }
    }

    fun onMenuAction(row: AppMenuEntry) {
        val app = _uiState.value.menuApp ?: return
        when (row.id) {
            AppMenuIds.EDIT_DETAILS -> _events.trySend(AppDrawerEvent.EditDetails(app.packageName))
            AppMenuIds.FAVORITE -> _events.trySend(AppDrawerEvent.ToggleFavorite(app.packageName, app.label))
            AppMenuIds.ADD_TO_CARD -> _events.trySend(AppDrawerEvent.AddToCard(app.packageName, app.label))
            AppMenuIds.APP_INFO -> appRepository.openAppInfo(app.packageName)
            AppMenuIds.MARK_GAME -> setMarkedAsGame(app, marked = true)
            AppMenuIds.UNMARK_GAME -> setMarkedAsGame(app, marked = false)
            // Guard rail: show an in-app confirmation before the system uninstall flow.
            AppMenuIds.UNINSTALL -> {
                _uiState.update { it.copy(menuApp = null, confirmUninstall = app) }
                return
            }
        }
        _uiState.update { it.copy(menuApp = null) }
    }

    // Marking puts the app in the Android Memory Card as a real game (counts in All Games, joins
    // gaming categories); unmarking demotes it to a decoration row (app_shortcut sentinel) so its
    // artwork, favorites and collection memberships survive a later re-mark.
    private fun setMarkedAsGame(app: InstalledApp, marked: Boolean) {
        viewModelScope.launch {
            val existing = gameRepository.getAppEntry(app.packageName)
            if (marked) {
                if (existing == null) {
                    gameRepository.upsert(
                        com.playfieldportal.core.domain.model.Game(
                            title         = app.label,
                            platformId    = ANDROID_PLATFORM_ID,
                            packageName   = app.packageName,
                            isManualEntry = true,
                            contentType   = com.playfieldportal.core.domain.model.GameContentType.GAME,
                        )
                    )
                } else {
                    gameRepository.upsert(existing.copy(
                        platformId  = ANDROID_PLATFORM_ID,
                        contentType = com.playfieldportal.core.domain.model.GameContentType.GAME,
                    ))
                }
            } else if (existing != null) {
                gameRepository.upsert(existing.copy(
                    platformId  = APP_SHORTCUT_PLATFORM_ID,
                    contentType = com.playfieldportal.core.domain.model.GameContentType.ANDROID_APP,
                ))
            }
            memoryCardRepository.recountGames(ANDROID_PLATFORM_ID)
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

        // The uninstall confirm is the shared modal: the screen sends every press to its host, which
        // opens on Cancel and owns the cursor. A press that still lands here only backs out — SELECT
        // must never confirm, since it is the press that opened the prompt.
        state.confirmUninstall?.let {
            if (action == GamepadAction.BACK) cancelUninstall()
            return
        }

        // The menu captures input while open. The shared PSP-panel rules: clamp, Triangle/Back
        // close, and each row's own cue. BACK only pops the menu — XMBViewModel forwards BACK
        // here, so it never closes the drawer while the menu is open.
        state.menuApp?.let {
            val actions = state.menuRows
            // X (Square) dismisses the menu too, as before; the panel's rules do not use it.
            if (action == GamepadAction.CHANGE_SORT) {
                menuSound.play(MenuSound.BACK)
                closeAppMenu()
                return
            }
            val index = state.menuIndex
            when (val outcome = PspMenuNav.handle(
                action, index, actions.size, depth = 0, actions.getOrNull(index)?.toPspMenuRow()?.cue ?: PspMenuCue.CONFIRM, MenuSoundSink { menuSound.play(it) },
            )) {
                is PspMenuOutcome.Moved -> _uiState.update { s -> s.copy(menuIndex = outcome.index) }
                PspMenuOutcome.Activate -> actions.getOrNull(index)?.let(::onMenuAction)
                PspMenuOutcome.Up, PspMenuOutcome.Close -> closeAppMenu()
                PspMenuOutcome.Ignored -> Unit
            }
            return
        }

        // L1/R1 — cycle through the filter tabs. Checked BEFORE the empty-grid guard on purpose:
        // a filter with zero apps (Recently Used before usage access is granted, or any list
        // emptied by a search) must never strand the cursor — category cycling always works, so
        // the user can always move out of an empty section.
        if (action == GamepadAction.PREV_CATEGORY || action == GamepadAction.NEXT_CATEGORY) {
            val filters = AppFilter.values()
            val idx = filters.indexOf(state.activeFilter)
            val target = if (action == GamepadAction.PREV_CATEGORY) idx - 1 else idx + 1
            if (target in filters.indices) setFilter(filters[target])
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
            GamepadAction.OPEN_CONTEXT_MENU -> openAppMenuForSelected()
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
            // (L1/R1 category cycling is handled above the empty-grid guard.)
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

        // Compute per-filter counts (unfiltered by search query) for the category rail.
        val counts = AppFilter.values().associateWith { filter ->
            state.allApps.count { app ->
                when (filter) {
                    AppFilter.ALL       -> true
                    AppFilter.GAMES     -> app.isGame
                    AppFilter.EMULATORS -> app.isEmulator
                    AppFilter.RECENT    -> app.lastUsedAt > 0L
                }
            }
        }

        _uiState.update { it.copy(visibleApps = filtered, filterCounts = counts) }
    }
}
