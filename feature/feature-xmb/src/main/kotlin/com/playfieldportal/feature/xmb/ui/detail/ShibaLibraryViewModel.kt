package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.AwaitingSyncGame
import com.playfieldportal.core.domain.achievement.GameStanding
import com.playfieldportal.core.domain.achievement.LibraryStanding
import com.playfieldportal.core.domain.achievement.UntrackedGame
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.achievements.match.RaConsole
import com.playfieldportal.feature.xmb.viewmodel.ShibaLibraryMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Achievements library (Tracked Games / Untracked Games) ────────────────────
//
// The PS3-trophy-inspired browser from docs/plans/PFP_Achievements_Screen_Design.md: a permanent
// Search row at navigation position 0, one full-width row per game below it, a Triangle Options
// menu (Filter and Provider lists, the Icon Display pattern), and L/R switching between the two
// sibling views. Focus is held by a row's stable id, never by its list index, so sorting, filtering
// and data refreshes keep the cursor on the same game whenever it is still listed.

/** Provider filter for Tracked Games: every provider PFP can track, plus All. */
enum class LibraryProviderFilter(val provider: AchievementProvider?) {
    ALL(null),
    RETRO(AchievementProvider.RETRO_ACHIEVEMENTS),
    STEAM(AchievementProvider.STEAM),
    LOCAL(AchievementProvider.LOCAL_STEAM),
    VITA(AchievementProvider.VITA_TROPHY),
    ;

    val label: String get() = provider?.let(::providerLabel) ?: "All"
}

/** Sort field for the library list, chosen from the Options menu's Filter list. */
enum class LibrarySortField(
    val label: String,
    private val ascendingLabel: String,
    private val descendingLabel: String,
) {
    TITLE("Title", "A–Z", "Z–A"),
    PROGRESS("Progress", "Lowest First", "Highest First"),
    PLATFORM("Platform", "A–Z", "Z–A"),
    ;

    /** The field and direction as one choice, e.g. "Title A–Z". */
    fun choiceLabel(ascending: Boolean): String = "$label ${if (ascending) ascendingLabel else descendingLabel}"
}

/** A second-level list in the Options menu, opened from its root row (the Icon Display pattern). */
enum class LibraryOptionGroup(val title: String) { FILTER("Filter"), PROVIDER("Provider") }

/** What an Options row does. */
sealed interface LibraryOption {
    /** A root row: opens its list. */
    data class OpenGroup(val group: LibraryOptionGroup) : LibraryOption
    data class Sort(val field: LibrarySortField, val ascending: Boolean) : LibraryOption
    data class Provider(val filter: LibraryProviderFilter) : LibraryOption
}

/** A row of the Options menu: its label, action, and whether it is the active choice. */
data class LibraryOptionRow(
    val label: String,
    val option: LibraryOption,
    val checked: Boolean = false,
)

/**
 * The open Options menu: the root list when [group] is null, otherwise that group's choices. Its
 * cursor is separate from the game list's, which it leaves untouched.
 */
data class LibraryOptionsMenu(
    val selectedIndex: Int = 0,
    val group: LibraryOptionGroup? = null,
) {
    val title: String get() = group?.title ?: "Options"
}

/** Earned coins by tier, in the design's display order (Platinum, Gold, Silver, Bronze). */
@Immutable
data class LibraryCoinCounts(
    val platinum: Int = 0,
    val gold: Int = 0,
    val silver: Int = 0,
    val bronze: Int = 0,
) {
    val total: Int get() = platinum + gold + silver + bronze
}

/** The Tracked Games header summary: Shiba Level, progress to the next level, and coin totals. */
@Immutable
data class LibrarySummary(
    val level: Int = 1,
    val nextLevelFraction: Float = 0f,
    val coins: LibraryCoinCounts = LibraryCoinCounts(),
) {
    val platinum: Int get() = coins.platinum
    val gold: Int get() = coins.gold
    val silver: Int get() = coins.silver
    val bronze: Int get() = coins.bronze
    val total: Int get() = coins.total
}

/**
 * One game row. Tracked rows show progress and coins and open their achievements through
 * [coinsTarget]. Untracked rows show [reason] instead; their [coinsTarget] is set only when an
 * existing provider match flow can handle the game, which is what Confirm then opens.
 */
@Immutable
data class ShibaLibraryRow(
    val id: String,
    val coinsTarget: ShibaCoinsTarget?,
    val title: String,
    val platformLabel: String,
    /** The achievement provider (tracked rows only). Drives the provider filter. */
    val provider: AchievementProvider?,
    /** Platform grouping key for the Platform sort. Steam and Local Steam both group as "Windows". */
    val platformSortKey: String,
    /** The game's ICON0 (144:80). Null draws the default ICON0 tile; no other art stands in. */
    val icon0Uri: String?,
    val progress: Float,
    val coins: LibraryCoinCounts,
    val reason: String?,
    /** A matched, installed game with no achievement data yet (e.g. after a clear). */
    val awaitingSync: Boolean = false,
) {
    /** Tracked rows show progress; [reason] rows (untracked, or awaiting sync) show the reason. */
    val isTracked: Boolean get() = reason == null

    /** An untracked game Confirm can send to the existing match flow. */
    val canAttemptMatch: Boolean get() = !isTracked && !awaitingSync && coinsTarget != null
}

data class ShibaLibraryUiState(
    val mode: ShibaLibraryMode = ShibaLibraryMode.TRACKED,
    val rows: List<ShibaLibraryRow> = emptyList(),
    /** The focused game's [ShibaLibraryRow.id], or null when Search (position 0) has focus. */
    val focusedRowId: String? = null,
    val query: String = "",
    /** Search is in text-entry mode (the keyboard is up). */
    val searchEditing: Boolean = false,
    val providerFilter: LibraryProviderFilter = LibraryProviderFilter.ALL,
    val sortField: LibrarySortField = LibrarySortField.TITLE,
    val sortAscending: Boolean = true,
    /** The Triangle Options menu, while open. It owns controller input. */
    val options: LibraryOptionsMenu? = null,
    val trackedCount: Int = 0,
    val untrackedCount: Int = 0,
    val summary: LibrarySummary = LibrarySummary(),
    val closed: Boolean = false,
    /** An entry the user activated; the screen opens its Shiba Coins page and calls onOpenHandled. */
    val openCoins: ShibaCoinsTarget? = null,
) {
    /** Navigation position: 0 is Search, 1 is the first game. */
    val focusPosition: Int get() = focusedRowId?.let { id -> rows.indexOfFirst { it.id == id } + 1 } ?: 0

    val focused: ShibaLibraryRow? get() = rows.getOrNull(focusPosition - 1)
    val searchFocused: Boolean get() = focused == null

    /** The provider filter only applies to tracked entries (untracked rows have no provider). */
    val showProviderFilter: Boolean get() = mode == ShibaLibraryMode.TRACKED

    /** Untracked games have no progress, so Progress is not offered there. */
    val availableSortFields: List<LibrarySortField>
        get() = when (mode) {
            ShibaLibraryMode.TRACKED -> LibrarySortField.entries
            ShibaLibraryMode.UNTRACKED -> LibrarySortField.entries - LibrarySortField.PROGRESS
        }

    val effectiveSortField: LibrarySortField
        get() = sortField.takeIf { it in availableSortFields } ?: LibrarySortField.TITLE

    val effectiveProviderFilter: LibraryProviderFilter
        get() = if (showProviderFilter) providerFilter else LibraryProviderFilter.ALL

    val optionRows: List<LibraryOptionRow> get() = libraryOptionRows(this)

    val emptyMessage: String
        get() = when {
            query.isNotBlank() -> "No games match \"${query.trim()}\"."
            mode == ShibaLibraryMode.TRACKED -> "No tracked games yet."
            else -> "Every eligible game is tracked."
        }
}

/**
 * The Options menu rows for [state], shaped like the XMB's Icon Display menu: the root names each
 * list with its current choice ("Filter (Title A–Z)", "Provider (All)"), and a list checks the
 * active choice. Provider is offered in Tracked Games only.
 */
fun libraryOptionRows(state: ShibaLibraryUiState): List<LibraryOptionRow> = when (state.options?.group) {
    null -> buildList {
        add(
            LibraryOptionRow(
                label = "Filter (${state.effectiveSortField.choiceLabel(state.sortAscending)})",
                option = LibraryOption.OpenGroup(LibraryOptionGroup.FILTER),
            ),
        )
        if (state.showProviderFilter) {
            add(
                LibraryOptionRow(
                    label = "Provider (${state.providerFilter.label})",
                    option = LibraryOption.OpenGroup(LibraryOptionGroup.PROVIDER),
                ),
            )
        }
    }
    LibraryOptionGroup.FILTER -> state.availableSortFields.flatMap { field ->
        // Progress reads best-first, so its descending choice leads.
        val directions = if (field == LibrarySortField.PROGRESS) listOf(false, true) else listOf(true, false)
        directions.map { ascending ->
            LibraryOptionRow(
                label = field.choiceLabel(ascending),
                option = LibraryOption.Sort(field, ascending),
                checked = field == state.effectiveSortField && ascending == state.sortAscending,
            )
        }
    }
    LibraryOptionGroup.PROVIDER -> LibraryProviderFilter.entries.map { filter ->
        LibraryOptionRow(filter.label, LibraryOption.Provider(filter), checked = filter == state.providerFilter)
    }
}

/**
 * The helper footer for [state]. Confirm is named for what it would do on the focused element and
 * is left out when it would do nothing; text entry and the Options menu replace the page hints.
 */
fun shibaLibraryHelperItems(state: ShibaLibraryUiState): List<ControllerPromptItem> = when {
    state.options != null -> listOf(
        ControllerPromptItem(GamepadAction.SELECT, "Select"),
        ControllerPromptItem(GamepadAction.BACK, "Close"),
    )
    state.searchEditing -> listOf(ControllerPromptItem(GamepadAction.BACK, "Done"))
    else -> buildList {
        val focused = state.focused
        val confirm = when {
            focused == null -> "Type"
            focused.isTracked || focused.awaitingSync -> "View Achievements"
            focused.canAttemptMatch -> "Attempt Match"
            else -> null
        }
        confirm?.let { add(ControllerPromptItem(GamepadAction.SELECT, it)) }
        add(ControllerPromptItem(GamepadAction.CHANGE_SORT, "Search"))
        add(ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Options"))
        add(ControllerPromptItem(listOf(GamepadAction.PREV_CATEGORY, GamepadAction.NEXT_CATEGORY), "Change View"))
        add(ControllerPromptItem(GamepadAction.BACK, "Back"))
    }
}

@HiltViewModel
class ShibaLibraryViewModel @Inject constructor(
    private val gameRepository: GameRepository,
    private val achievementRepository: AchievementController,
) : ViewModel() {

    private val _state = MutableStateFlow(ShibaLibraryUiState())
    val uiState: StateFlow<ShibaLibraryUiState> = _state.asStateFlow()

    private var latest: Pair<LibraryStanding, Map<Long, Game>>? = null
    private var currentModeRows: List<ShibaLibraryRow> = emptyList()
    private var collectJob: Job? = null

    /** Set when a view is entered fresh: the next row push puts the cursor on its first game. */
    private var focusFirstRow = true

    fun load(mode: ShibaLibraryMode) {
        val returning = _state.value.mode == mode && currentModeRows.isNotEmpty()
        _state.update {
            // Re-entering the same view (e.g. back from a game's coins page) keeps the user's place;
            // a different view starts on its first game with a clean query.
            if (returning) it.copy(closed = false)
            else it.copy(mode = mode, closed = false, focusedRowId = null, query = "", searchEditing = false, options = null)
        }
        if (!returning) focusFirstRow = true
        rebuild()
        if (collectJob != null) return
        collectJob = viewModelScope.launch {
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

    /** Controller input forwarded from the shell while this screen is open. */
    fun handleGamepadAction(action: GamepadAction) {
        val s = _state.value
        if (s.options != null) {
            handleOptionsAction(action)
            return
        }
        if (s.searchEditing) {
            // An open keyboard receives key events before the shell does, so a pad press arriving
            // here means the keyboard is already gone: end text entry. Back and Confirm stop there;
            // anything else also does its normal job.
            onSearchEditEnded()
            if (action == GamepadAction.BACK || action == GamepadAction.SELECT) return
        }
        when (action) {
            GamepadAction.NAVIGATE_UP -> moveFocus(-1)
            GamepadAction.NAVIGATE_DOWN -> moveFocus(1)
            // L / R switch views. LEFT / RIGHT stay as quiet aliases, the binding before the redesign.
            GamepadAction.PREV_CATEGORY, GamepadAction.NAVIGATE_LEFT -> switchSibling(-1)
            GamepadAction.NEXT_CATEGORY, GamepadAction.NAVIGATE_RIGHT -> switchSibling(1)
            GamepadAction.SELECT -> activateFocused()
            // Square always means "go to Search"; pressed on Search itself it starts typing.
            GamepadAction.CHANGE_SORT -> if (s.searchFocused) startSearchEdit() else focusSearch()
            GamepadAction.OPEN_CONTEXT_MENU -> openOptions()
            GamepadAction.BACK -> close()
            GamepadAction.HOME -> Unit
        }
    }

    // ── Focus ──────────────────────────────────────────────────────────────────

    private fun moveFocus(delta: Int) = _state.update { s ->
        val position = (s.focusPosition + delta).coerceIn(0, s.rows.size)
        s.copy(focusedRowId = s.rows.getOrNull(position - 1)?.id)
    }

    fun focusSearch() = _state.update { it.copy(focusedRowId = null) }

    /** Touch: the first tap on a row focuses it; tapping the focused row activates it. */
    fun onRowClick(rowId: String) {
        val s = _state.value
        if (s.searchEditing) onSearchEditEnded()
        if (s.focusedRowId == rowId) activateFocused()
        else if (s.rows.any { it.id == rowId }) _state.update { it.copy(focusedRowId = rowId) }
    }

    /** Touch: tapping Search goes straight to typing. */
    fun onSearchClick() = startSearchEdit()

    /** Confirm: Search starts typing; a game opens its achievements or its match flow, if it has one. */
    private fun activateFocused() {
        val row = _state.value.focused ?: return startSearchEdit()
        val target = row.coinsTarget ?: return
        _state.update { it.copy(openCoins = target) }
    }

    /** Clears the open request once the screen has acted on it. */
    fun onOpenHandled() = _state.update { it.copy(openCoins = null) }

    // ── Search ─────────────────────────────────────────────────────────────────

    fun startSearchEdit() = _state.update { it.copy(focusedRowId = null, searchEditing = true) }

    /** Text entry ended (keyboard dismissed, IME action, or a pad press). The query is kept. */
    fun onSearchEditEnded() = _state.update { it.copy(searchEditing = false) }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        pushRows()
    }

    // ── Options menu ───────────────────────────────────────────────────────────

    /** Opens the Options root on its first row. */
    fun openOptions() = _state.update { it.copy(options = LibraryOptionsMenu(), searchEditing = false) }

    /** Swaps the menu to [group]'s list, with the cursor on its active choice. */
    private fun openOptionGroup(group: LibraryOptionGroup) = _state.update { s ->
        val listed = s.copy(options = LibraryOptionsMenu(group = group))
        val active = listed.optionRows.indexOfFirst { it.checked }.coerceAtLeast(0)
        listed.copy(options = LibraryOptionsMenu(selectedIndex = active, group = group))
    }

    fun closeOptions() = _state.update { it.copy(options = null) }

    private fun handleOptionsAction(action: GamepadAction) {
        val menu = _state.value.options ?: return
        when (action) {
            GamepadAction.NAVIGATE_UP -> moveOptionsCursor(menu, -1)
            GamepadAction.NAVIGATE_DOWN -> moveOptionsCursor(menu, 1)
            GamepadAction.SELECT -> onOptionActivated(menu.selectedIndex)
            GamepadAction.BACK, GamepadAction.OPEN_CONTEXT_MENU -> closeOptions()
            else -> Unit
        }
    }

    private fun moveOptionsCursor(menu: LibraryOptionsMenu, delta: Int) = _state.update { s ->
        val last = (s.optionRows.size - 1).coerceAtLeast(0)
        s.copy(options = menu.copy(selectedIndex = (menu.selectedIndex + delta).coerceIn(0, last)))
    }

    /**
     * Activates an Options row (controller Confirm or tap), as the Icon Display menu does: a root row
     * opens its list, and a choice applies and closes the menu.
     */
    fun onOptionActivated(index: Int) {
        val row = _state.value.optionRows.getOrNull(index) ?: return
        when (val option = row.option) {
            is LibraryOption.OpenGroup -> return openOptionGroup(option.group)
            is LibraryOption.Sort -> setSort(option.field, option.ascending)
            is LibraryOption.Provider -> setProviderFilter(option.filter)
        }
        closeOptions()
    }

    fun setProviderFilter(filter: LibraryProviderFilter) {
        _state.update { it.copy(providerFilter = filter) }
        pushRows()
    }

    fun setSort(field: LibrarySortField, ascending: Boolean) {
        _state.update { it.copy(sortField = field, sortAscending = ascending) }
        pushRows()
    }

    // ── Views ──────────────────────────────────────────────────────────────────

    fun switchSibling(dir: Int) {
        val siblings = ShibaLibraryMode.entries
        setMode(siblings[(_state.value.mode.ordinal + dir).mod(siblings.size)])
    }

    fun setMode(mode: ShibaLibraryMode) {
        if (mode == _state.value.mode) return
        _state.update { it.copy(mode = mode, focusedRowId = null, query = "", searchEditing = false) }
        focusFirstRow = true
        rebuild()
    }

    // ── Rows ───────────────────────────────────────────────────────────────────

    private fun rebuild() {
        val (standing, byId) = latest ?: return
        // Rows are built unsorted; the provider filter, query and sort all apply in pushRows, so a
        // filter or sort change never needs a rebuild.
        currentModeRows = when (_state.value.mode) {
            ShibaLibraryMode.TRACKED ->
                standing.tracked.map { it.toRow(it.libraryGameId?.let(byId::get)) } +
                    standing.awaitingSync.map { it.toRow(byId[it.gameId]) }
            ShibaLibraryMode.UNTRACKED -> standing.untracked.map { it.toRow(byId[it.gameId]) }
        }
        val wallet = standing.wallet
        val earned = standing.walletCounts
        _state.update {
            it.copy(
                trackedCount = standing.gamesTracked,
                untrackedCount = standing.untracked.size,
                summary = LibrarySummary(
                    level = wallet.level,
                    nextLevelFraction = wallet.levelProgress.fraction,
                    coins = LibraryCoinCounts(
                        platinum = standing.gamesMastered,
                        gold = earned.gold,
                        silver = earned.silver,
                        bronze = earned.bronze,
                    ),
                ),
            )
        }
        pushRows()
    }

    /**
     * Applies the provider filter, query and sort, then recovers focus: the same game if it is still
     * listed, otherwise the row now at the old position (clamped), otherwise Search.
     */
    private fun pushRows() {
        val s = _state.value
        val provider = s.effectiveProviderFilter.provider
        val needle = s.query.trim()
        val rows = currentModeRows
            .filter { provider == null || it.provider == provider }
            .filter { needle.isEmpty() || it.title.contains(needle, ignoreCase = true) }
            .sortedWith(sortComparator(s.effectiveSortField, s.sortAscending))

        val focusId = when {
            focusFirstRow -> rows.firstOrNull()?.id
            s.focusedRowId == null -> null
            rows.any { it.id == s.focusedRowId } -> s.focusedRowId
            rows.isEmpty() -> null
            else -> rows[(s.focusPosition - 1).coerceIn(0, rows.lastIndex)].id
        }
        // A fresh view waits for its first non-empty push (data may still be loading).
        if (focusFirstRow && rows.isNotEmpty()) focusFirstRow = false
        _state.update { it.copy(rows = rows, focusedRowId = focusId) }
    }

    // Title stays A–Z within a group for every sort; only the primary key's direction flips.
    private fun sortComparator(field: LibrarySortField, ascending: Boolean): Comparator<ShibaLibraryRow> {
        val byTitle = compareBy<ShibaLibraryRow> { it.title.lowercase() }
        return when (field) {
            LibrarySortField.TITLE -> if (ascending) byTitle else byTitle.reversed()
            LibrarySortField.PROGRESS ->
                if (ascending) compareBy<ShibaLibraryRow> { it.progress }.then(byTitle)
                else compareByDescending<ShibaLibraryRow> { it.progress }.then(byTitle)
            LibrarySortField.PLATFORM ->
                if (ascending) compareBy<ShibaLibraryRow> { it.platformSortKey.lowercase() }.then(byTitle)
                else compareByDescending<ShibaLibraryRow> { it.platformSortKey.lowercase() }.then(byTitle)
        }
    }

    private fun GameStanding.toRow(game: Game?) = ShibaLibraryRow(
        id = "${coins.provider.name}:$providerGameId",
        coinsTarget = libraryGameId?.let { ShibaCoinsTarget.LibraryGame(it) }
            ?: ShibaCoinsTarget.AccountEntry(coins.provider, providerGameId),
        title = game?.displayTitle ?: title,
        // A removed game keeps its cached coins as history; the label says why it never refreshes.
        platformLabel = (game?.platformId?.let(::platformDisplay) ?: providerLabel(coins.provider)) +
            if (isInstalled) "" else " · Not installed",
        provider = coins.provider,
        platformSortKey = platformGroupOf(coins.provider, game?.platformId),
        icon0Uri = game?.iconUri,
        progress = coins.progress,
        coins = LibraryCoinCounts(
            platinum = if (coins.isMastered) 1 else 0,
            gold = coins.earned.gold,
            silver = coins.earned.silver,
            bronze = coins.earned.bronze,
        ),
        reason = null,
    )

    // Matched but never synced: shown under Tracked as "Awaiting sync" rather than a false 0%.
    private fun AwaitingSyncGame.toRow(game: Game?) = ShibaLibraryRow(
        id = "${provider.name}:$providerGameId",
        coinsTarget = ShibaCoinsTarget.LibraryGame(gameId),
        title = game?.displayTitle ?: title,
        platformLabel = game?.platformId?.let(::platformDisplay) ?: providerLabel(provider),
        provider = provider,
        platformSortKey = platformGroupOf(provider, game?.platformId),
        icon0Uri = game?.iconUri,
        progress = 0f,
        coins = LibraryCoinCounts(),
        reason = "Awaiting sync",
        awaitingSync = true,
    )

    private fun UntrackedGame.toRow(game: Game?) = ShibaLibraryRow(
        id = "untracked:$gameId",
        // Only games an existing match flow handles get a target: Steam auto-match for Windows
        // games, RetroAchievements hash matching for supported consoles (see ShibaCoinsViewModel).
        coinsTarget = ShibaCoinsTarget.LibraryGame(gameId).takeIf { isMatchable(platformId) },
        title = game?.displayTitle ?: title,
        platformLabel = platformDisplay(platformId),
        provider = null,
        platformSortKey = platformGroupOf(null, platformId),
        icon0Uri = game?.iconUri,
        progress = 0f,
        coins = LibraryCoinCounts(),
        reason = reason,
    )

    private fun isMatchable(platformId: String): Boolean =
        platformId == "windows" || RaConsole.idFor(platformId) != null

    // Platform bucket for the Platform sort: Steam and Local Steam (and any windows-platform game)
    // all count as one "Windows" platform; everything else uses its platform's display name.
    private fun platformGroupOf(provider: AchievementProvider?, platformId: String?): String = when {
        provider == AchievementProvider.STEAM || provider == AchievementProvider.LOCAL_STEAM -> "Windows"
        platformId == "windows" -> "Windows"
        platformId != null -> platformDisplay(platformId)
        provider != null -> providerLabel(provider)
        else -> ""
    }
}

// A readable platform label (e.g. "snes" -> "Super Nintendo"); falls back to upper-case.
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
    "xbox" -> "Xbox"
    "x360" -> "Xbox 360"
    "windows" -> "Steam"
    else -> platformId.uppercase()
}
