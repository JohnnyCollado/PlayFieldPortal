package com.playfieldportal.feature.xmb.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.GameStanding
import com.playfieldportal.core.domain.achievement.UntrackedGame
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.xmb.viewmodel.ShibaLibraryMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Provider filter for the All Tracked view (Y / triangle cycles it). */
enum class LibraryProviderFilter(val label: String, val provider: AchievementProvider?) {
    ALL("All", null),
    RETRO("RetroAchievements", AchievementProvider.RETRO_ACHIEVEMENTS),
    STEAM("Steam", AchievementProvider.STEAM),
    LOCAL("Local", AchievementProvider.LOCAL_STEAM),
}

/** Sort field for the library list (X / square cycles field+direction). */
enum class LibrarySortField(val label: String) { TITLE("Title"), PROGRESS("Progress"), CONSOLE("Console") }

/**
 * One row in the fullscreen library. Tracked rows show coins/progress and open their coins
 * overlay via [coinsTarget]; untracked rows show a [reason] instead. Account-imported entries
 * have no library game — [inLibrary] is the hub's launchable-title marker.
 */
data class ShibaLibraryRow(
    val id: String,
    val coinsTarget: ShibaCoinsTarget?,
    val inLibrary: Boolean,
    val title: String,
    val platformLabel: String,
    // The achievement provider (tracked rows only); null for untracked rows. Drives the filter.
    val provider: AchievementProvider?,
    // Console grouping key for CONSOLE sort — Steam and Local Steam both bucket as "Windows".
    val consoleSortKey: String,
    val artworkUri: String?,
    val logoUri: String?,
    val progress: Float,
    val bronzeEarned: Int,
    val bronzeTotal: Int,
    val silverEarned: Int,
    val silverTotal: Int,
    val goldEarned: Int,
    val goldTotal: Int,
    val mastered: Boolean,
    val reason: String?,
) {
    val isTracked: Boolean get() = reason == null
}

data class ShibaLibraryUiState(
    val mode: ShibaLibraryMode = ShibaLibraryMode.TRACKED,
    val rows: List<ShibaLibraryRow> = emptyList(),
    val focusIndex: Int = 0,
    val query: String = "",
    // Provider filter (All Tracked view only); Y / triangle cycles it.
    val providerFilter: LibraryProviderFilter = LibraryProviderFilter.ALL,
    // Sort field + direction; X / square cycles field then direction.
    val sortField: LibrarySortField = LibrarySortField.TITLE,
    val sortAscending: Boolean = true,
    // Counts across all sibling views, for the header tabs.
    val trackedCount: Int = 0,
    val untrackedCount: Int = 0,
    // Wallet footer.
    val totalCoinScore: Int = 0,
    val level: Int = 1,
    val rank: String = "",
    val coinsIntoLevel: Int = 0,
    val coinsForNextLevel: Int = 0,
    val closed: Boolean = false,
    // A tracked entry the user activated (second tap / controller confirm) — the screen opens its
    // Shiba Coins overlay and calls [ShibaLibraryViewModel.onOpenHandled].
    val openCoins: ShibaCoinsTarget? = null,
) {
    val focused: ShibaLibraryRow? get() = rows.getOrNull(focusIndex)
    val siblings: List<ShibaLibraryMode> get() = ShibaLibraryMode.entries
    // The provider filter only makes sense for tracked entries (untracked rows have no provider).
    val showProviderFilter: Boolean get() = mode == ShibaLibraryMode.TRACKED
    val nextRewardCoins: Int get() = (coinsForNextLevel - coinsIntoLevel).coerceAtLeast(0)
    val levelFraction: Float
        get() = if (coinsForNextLevel <= 0) 0f else (coinsIntoLevel.toFloat() / coinsForNextLevel).coerceIn(0f, 1f)
}

@HiltViewModel
class ShibaLibraryViewModel @Inject constructor(
    private val gameRepository: GameRepository,
    private val achievementRepository: AchievementController,
) : ViewModel() {

    private val _state = MutableStateFlow(ShibaLibraryUiState())
    val uiState: StateFlow<ShibaLibraryUiState> = _state.asStateFlow()

    private var latest: Pair<com.playfieldportal.core.domain.achievement.LibraryStanding, Map<Long, Game>>? = null
    private var currentModeRows: List<ShibaLibraryRow> = emptyList()
    private var collecting = false

    fun load(mode: ShibaLibraryMode) {
        _state.update {
            // Re-entering the same mode (e.g. returning from a game's coins overlay) keeps the
            // user's place; a different mode starts from the top with a clean query.
            if (it.mode == mode && currentModeRows.isNotEmpty()) it.copy(closed = false)
            else it.copy(mode = mode, closed = false, focusIndex = 0, query = "")
        }
        rebuild()
        if (collecting) return
        collecting = true
        viewModelScope.launch {
            combine(
                achievementRepository.observeLibraryStanding(),
                gameRepository.observeGamesOnly(),
            ) { standing, games -> standing to games.associateBy { it.id } }
                .collect {
                    latest = it
                    rebuild()
                }
        }
    }

    fun close() = _state.update { it.copy(closed = true) }
    fun onClosedHandled() = _state.update { it.copy(closed = false) }

    /** Controller input forwarded from the shell while this overlay is open. */
    fun handleGamepadAction(action: GamepadAction) {
        val last = (_state.value.rows.size - 1).coerceAtLeast(0)
        when (action) {
            GamepadAction.NAVIGATE_UP -> _state.update { it.copy(focusIndex = (it.focusIndex - 1).coerceIn(0, last)) }
            GamepadAction.NAVIGATE_DOWN -> _state.update { it.copy(focusIndex = (it.focusIndex + 1).coerceIn(0, last)) }
            // LEFT / RIGHT move between the sibling views (All Tracked <-> Untracked), like the
            // other XMB leveled-sibling drill-ins.
            GamepadAction.NAVIGATE_LEFT -> switchSibling(-1)
            GamepadAction.NAVIGATE_RIGHT -> switchSibling(1)
            GamepadAction.SELECT -> openFocused()
            // Y / triangle cycles the provider filter (All Tracked view only).
            GamepadAction.BUTTON_Y, GamepadAction.LONG_PRESS -> cycleProviderFilter()
            // X / square cycles the sort field and direction.
            GamepadAction.CHANGE_SORT, GamepadAction.OPEN_TASK_TRAY -> cycleSort()
            GamepadAction.BACK -> close()
            else -> Unit
        }
    }

    /** Y / triangle: advance the provider filter (no-op outside the All Tracked view). */
    fun cycleProviderFilter() {
        if (!_state.value.showProviderFilter) return
        val entries = LibraryProviderFilter.entries
        setProviderFilter(entries[(_state.value.providerFilter.ordinal + 1) % entries.size])
    }

    fun setProviderFilter(filter: LibraryProviderFilter) {
        _state.update { it.copy(providerFilter = filter, focusIndex = 0) }
        pushRows()
    }

    /**
     * X / square: step through the six sort states in order — each field ascending then
     * descending — so one button reaches every combination: Title ↑↓, Progress ↑↓, Console ↑↓.
     * Ascending flips to descending on the same field; descending advances to the next field.
     */
    fun cycleSort() {
        _state.update {
            if (it.sortAscending) {
                it.copy(sortAscending = false, focusIndex = 0)
            } else {
                val next = LibrarySortField.entries[(it.sortField.ordinal + 1) % LibrarySortField.entries.size]
                it.copy(sortField = next, sortAscending = true, focusIndex = 0)
            }
        }
        pushRows()
    }

    fun setSort(field: LibrarySortField, ascending: Boolean) {
        _state.update { it.copy(sortField = field, sortAscending = ascending, focusIndex = 0) }
        pushRows()
    }

    /** Opens the focused tracked entry's Shiba Coins overlay (untracked rows have no coins to show). */
    fun openFocused() {
        val target = _state.value.focused?.coinsTarget ?: return
        _state.update { it.copy(openCoins = target) }
    }

    /** Touch: first tap selects a row (updating the panel); tapping the selected row opens it. */
    fun onRowClick(index: Int) {
        if (index == _state.value.focusIndex) openFocused() else setFocus(index)
    }

    /** Clears the open request once the screen has acted on it. */
    fun onOpenHandled() = _state.update { it.copy(openCoins = null) }

    fun switchSibling(dir: Int) {
        val siblings = ShibaLibraryMode.entries
        setMode(siblings[(_state.value.mode.ordinal + dir + siblings.size) % siblings.size])
    }

    fun setMode(mode: ShibaLibraryMode) {
        if (mode == _state.value.mode) return
        _state.update { it.copy(mode = mode, focusIndex = 0, query = "") }
        rebuild()
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        pushRows()
    }

    fun setFocus(index: Int) = _state.update { it.copy(focusIndex = index.coerceIn(0, (it.rows.size - 1).coerceAtLeast(0))) }

    private fun rebuild() {
        val (standing, byId) = latest ?: return
        // Rows are built unsorted here; the provider filter, query, and active sort are all applied
        // in pushRows so a filter/sort change never needs a full rebuild.
        currentModeRows = when (_state.value.mode) {
            ShibaLibraryMode.TRACKED ->
                standing.tracked.map { it.toRow(it.libraryGameId?.let(byId::get)) }
            ShibaLibraryMode.UNTRACKED ->
                standing.untracked.map { it.toRow(byId[it.gameId]) }
        }
        val w = standing.wallet
        val p = w.levelProgress
        _state.update {
            it.copy(
                trackedCount = standing.gamesTracked,
                untrackedCount = standing.untracked.size,
                totalCoinScore = w.totalCoins,
                level = w.level,
                rank = w.rank.label,
                coinsIntoLevel = p.coinsIntoLevel,
                coinsForNextLevel = p.coinsForNextLevel,
            )
        }
        pushRows()
    }

    // Applies provider filter (tracked only), search query, then the active sort to the mode's rows.
    private fun pushRows() {
        val s = _state.value
        val providerFiltered =
            if (s.showProviderFilter) s.providerFilter.provider
                ?.let { p -> currentModeRows.filter { it.provider == p } } ?: currentModeRows
            else currentModeRows

        val q = s.query.trim().lowercase()
        val queried = if (q.isEmpty()) providerFiltered
        else providerFiltered.filter { it.title.lowercase().contains(q) }

        val sorted = queried.sortedWith(sortComparator(s.sortField, s.sortAscending))
        _state.update {
            it.copy(rows = sorted, focusIndex = it.focusIndex.coerceIn(0, (sorted.size - 1).coerceAtLeast(0)))
        }
    }

    // Title stays A–Z within a group for every sort; only the primary key's direction flips. For
    // CONSOLE that means consoles order A→Z (asc) or Z→A (desc) with games alphabetical inside.
    private fun sortComparator(field: LibrarySortField, ascending: Boolean): Comparator<ShibaLibraryRow> {
        val byTitle = compareBy<ShibaLibraryRow> { it.title.lowercase() }
        return when (field) {
            LibrarySortField.TITLE -> if (ascending) byTitle else byTitle.reversed()
            LibrarySortField.PROGRESS ->
                if (ascending) compareBy<ShibaLibraryRow> { it.progress }.then(byTitle)
                else compareByDescending<ShibaLibraryRow> { it.progress }.then(byTitle)
            LibrarySortField.CONSOLE ->
                if (ascending) compareBy<ShibaLibraryRow> { it.consoleSortKey.lowercase() }.then(byTitle)
                else compareByDescending<ShibaLibraryRow> { it.consoleSortKey.lowercase() }.then(byTitle)
        }
    }

    private fun GameStanding.toRow(game: Game?) = ShibaLibraryRow(
        id = "${coins.provider.name}:$providerGameId",
        coinsTarget = libraryGameId?.let { ShibaCoinsTarget.LibraryGame(it) }
            ?: ShibaCoinsTarget.AccountEntry(coins.provider, providerGameId),
        inLibrary = inLibrary,
        title = game?.displayTitle ?: title,
        platformLabel = game?.platformId?.let(::platformDisplay) ?: providerLabel(coins.provider),
        provider = coins.provider,
        consoleSortKey = consoleGroupOf(coins.provider, game?.platformId),
        artworkUri = game?.artworkUri ?: iconUrl,
        logoUri = game?.logoUri,
        progress = coins.progress,
        bronzeEarned = coins.earned.bronze, bronzeTotal = coins.total.bronze,
        silverEarned = coins.earned.silver, silverTotal = coins.total.silver,
        goldEarned = coins.earned.gold, goldTotal = coins.total.gold,
        mastered = coins.isMastered,
        reason = null,
    )

    private fun UntrackedGame.toRow(game: Game?) = ShibaLibraryRow(
        id = "untracked:$gameId",
        coinsTarget = null,
        inLibrary = true,
        title = game?.displayTitle ?: title,
        platformLabel = platformDisplay(platformId),
        provider = null,
        consoleSortKey = consoleGroupOf(null, platformId),
        artworkUri = game?.artworkUri,
        logoUri = game?.logoUri,
        progress = 0f,
        bronzeEarned = 0, bronzeTotal = 0,
        silverEarned = 0, silverTotal = 0,
        goldEarned = 0, goldTotal = 0,
        mastered = false,
        reason = reason,
    )

    // Console bucket for CONSOLE sort: Steam and Local Steam (and any windows-platform game) all
    // count as one "Windows" console; everything else uses its platform's display name.
    private fun consoleGroupOf(provider: AchievementProvider?, platformId: String?): String = when {
        provider == AchievementProvider.STEAM || provider == AchievementProvider.LOCAL_STEAM -> "Windows"
        platformId == "windows" -> "Windows"
        platformId != null -> platformDisplay(platformId)
        provider != null -> providerLabel(provider)
        else -> ""
    }
}

// A readable platform label for the panel (e.g. "snes" -> "Super Nintendo"); falls back to upper-case.
internal fun platformDisplay(platformId: String): String = when (platformId) {
    "snes" -> "Super Nintendo"
    "nes" -> "Nintendo"
    "n64" -> "Nintendo 64"
    "gb" -> "Game Boy"
    "gbc" -> "Game Boy Color"
    "gba" -> "Game Boy Advance"
    "nds" -> "Nintendo DS"
    "n3ds" -> "Nintendo 3DS"
    "gc" -> "GameCube"
    "wii" -> "Wii"
    "megadrive" -> "Genesis"
    "mastersystem" -> "Master System"
    "gamegear" -> "Game Gear"
    "psx" -> "PlayStation"
    "ps2" -> "PlayStation 2"
    "psp" -> "PSP"
    "psvita" -> "PS Vita"
    "x360" -> "Xbox 360"
    "windows" -> "Steam"
    else -> platformId.uppercase()
}
