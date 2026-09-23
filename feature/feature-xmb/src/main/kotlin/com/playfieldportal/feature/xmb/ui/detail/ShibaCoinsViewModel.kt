package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.database.entity.AccountAchievementEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.GameCoins
import com.playfieldportal.core.domain.achievement.LocalCopyOwnership
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.match.AchievementAutoMatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Per-game achievements page ────────────────────────────────────────────────
//
// docs/plans/PFP_Achievements_Game_Page_Implementation_Plan.md: the page that opens when you
// confirm a tracked game, rebuilt to match the Tracked/Untracked browser. The contract is the
// library's: a pinned Search row at navigation position 0, stable-id focus so sorting, searching,
// switching view and data refreshes keep the cursor on the same coin, L1/R1 across the All /
// Earned / Locked views, and a modal Triangle Options menu that owns Sort, Sync Now and Change
// Match. What is NOT rebuilt: loading, syncing, matching and the Auto-Match flow, which are
// carried over unchanged.

enum class CoinSort(val label: String) { TIER("Tier"), EARNED("Earned"), RAREST("Rarest") }

/** The three views of a game's coins, switched with L1/R1 and shown as tabs beside Search. */
enum class CoinFilter(val label: String) { ALL("All"), EARNED("Earned"), LOCKED("Locked") }

/** The Auto-Match flow's current step (unlinked Steam-platform games only). */
enum class AutoMatchStep { CONFIRM_COPY, ENTER_APPID }

/** The synthetic first row: the set-completion award, which is not one of the provider's coins. */
internal const val PLATINUM_ROW_ID = "platinum"

/** The unlinked game's single row: the Auto-Match panel, which stands in for the whole list. */
internal const val LINK_ROW_ID = "link"

/** What a redacted hidden coin matches on, so search can never give away its real title. */
private const val REDACTED_TITLE = "Hidden Coin"

/** One coin as the dedicated screen renders it. */
@Immutable
data class CoinRow(
    val id: String,
    val tier: ShibaTier,
    val title: String,
    val description: String,
    val globalRarity: Double,
    val iconUrl: String?,
    val isHidden: Boolean,
    val isEarned: Boolean,
    val earnedAt: Long?,
) {
    /** A hidden coin the user hasn't earned can be revealed and re-hidden; an earned one can't. */
    val isHideable: Boolean get() = isHidden && !isEarned
}

/** A row of the coin list. Focus, the footer and Confirm all key off which kind this is. */
@Immutable
sealed interface CoinListItem {
    val id: String

    /** The Platinum Crown: earned by taking every other coin in the game. */
    data class Platinum(val earned: Int, val total: Int, val isMastered: Boolean) : CoinListItem {
        override val id: String get() = PLATINUM_ROW_ID
    }

    data class Coin(val coin: CoinRow) : CoinListItem {
        override val id: String get() = coin.id
    }

    /** An unlinked game has no coins to list — the whole list area is its Auto-Match panel. */
    data object LinkPanel : CoinListItem {
        override val id: String get() = LINK_ROW_ID
    }
}

/** Coins per view, counted off the whole set so they don't move while you search. */
@Immutable
data class CoinViewCounts(val all: Int = 0, val earned: Int = 0, val locked: Int = 0) {
    fun forView(filter: CoinFilter): Int = when (filter) {
        CoinFilter.ALL -> all
        CoinFilter.EARNED -> earned
        CoinFilter.LOCKED -> locked
    }
}

/** A second-level list in the Options menu, opened from its root row (the Icon Display pattern). */
enum class CoinOptionGroup(val title: String) { SORT("Sort") }

/** What an Options row does. */
sealed interface CoinOption {
    /** A root row: opens its list. */
    data class OpenGroup(val group: CoinOptionGroup) : CoinOption
    data class Sort(val sort: CoinSort) : CoinOption
    data object SyncNow : CoinOption
    data object ChangeMatch : CoinOption
}

/** A row of the Options menu: its label, action, and whether it is the active choice. */
data class CoinOptionRow(
    val label: String,
    val option: CoinOption,
    val checked: Boolean = false,
)

/**
 * The open Options menu: the root list when [group] is null, otherwise that group's choices. Its
 * cursor is separate from the coin list's, which it leaves untouched.
 */
data class CoinOptionsMenu(
    val selectedIndex: Int = 0,
    val group: CoinOptionGroup? = null,
) {
    val title: String get() = group?.title ?: "Options"
}

@Immutable
data class ShibaCoinsUiState(
    val title: String = "",
    val platformLabel: String = "",
    val provider: AchievementProvider = AchievementProvider.RETRO_ACHIEVEMENTS,
    val linked: Boolean = false,
    // LOCAL_STEAM only: owned-vs-local classification from the link row; null = unknown (the
    // owned-games cache was never populated) and the UI stays silent about ownership.
    val ownership: LocalCopyOwnership? = null,
    // An account entry with no library game: syncable, but nothing to link or match.
    val accountOnly: Boolean = false,
    // False for a game matched once but since removed: its cached coins show as history, with no
    // refresh (selective sync never refreshes games that are not on this device).
    val installed: Boolean = true,
    val summary: GameCoins? = null,
    val coins: List<CoinRow> = emptyList(),
    // Sorted, filtered and searched view the screen renders; kept in sync by withRows().
    val displayed: List<CoinRow> = emptyList(),
    // The list as the screen draws it: the Platinum Crown row, the displayed coins, or — for an
    // unlinked game — the single link panel. Stored rather than derived so a recomposition never
    // re-sorts the set, and so focus recovery can compare the old list against the new one.
    val rows: List<CoinListItem> = emptyList(),
    val viewCounts: CoinViewCounts = CoinViewCounts(),
    val sort: CoinSort = CoinSort.TIER,
    /** The active view. L1/R1 cycle it; the tabs beside Search show it. */
    val filter: CoinFilter = CoinFilter.ALL,
    val query: String = "",
    /** Search is in text-entry mode (the keyboard is up). */
    val searchEditing: Boolean = false,
    /**
     * The focused row's id, or null when the pinned Search row (position 0) has focus. Ids are
     * stable — a coin id, [PLATINUM_ROW_ID] or [LINK_ROW_ID] — never a list index, so sorting,
     * searching and data refreshes keep the cursor on the same row.
     */
    val focusedRowId: String? = null,
    /** The Triangle Options menu, while open. It owns controller input. */
    val options: CoinOptionsMenu? = null,
    // Hidden coins the user chose to reveal (confirm/tap toggles). Session-only: cleared on open.
    val revealedIds: Set<String> = emptySet(),
    val isSyncing: Boolean = false,
    /** When the provider set was last synced, for the header's "Synced 4 min ago" line. */
    val lastSyncedAt: Long? = null,
    // Auto-Match flow (unlinked Steam-platform games): confirm whether the copy is a legit Steam
    // one, then fall through to manual appid entry when no automatic match is found.
    val autoMatchStep: AutoMatchStep? = null,
    // Controller selection on the confirm prompt: true = "Yes, legit Steam copy".
    val autoMatchYes: Boolean = true,
    // The branch the user picked, so manual appid entry links the matching provider.
    val isMatching: Boolean = false,
    val message: String? = null,
    val closed: Boolean = false,
) {
    /** Navigation position: 0 is the pinned Search row, 1 is the first list row. */
    val focusPosition: Int
        get() = focusedRowId?.let { id -> rows.indexOfFirst { it.id == id } + 1 } ?: 0

    val focused: CoinListItem? get() = rows.getOrNull(focusPosition - 1)

    val searchFocused: Boolean get() = focused == null

    /** The focused coin, when a real coin row (not Platinum, not the link panel) has focus. */
    val focusedCoin: CoinRow? get() = (focused as? CoinListItem.Coin)?.coin

    /** Whether the focused coin is currently revealed (only meaningful for a hideable coin). */
    val focusedRevealed: Boolean get() = focusedCoin?.let { it.id in revealedIds } == true

    /** An unlinked library game: nothing to list until it is matched to a provider id. */
    val showLinkPanel: Boolean get() = !linked && !accountOnly

    /** Only a linked Steam library game has a user-provided match that can be changed. */
    val hasChangeMatch: Boolean
        get() = linked && !accountOnly && provider == AchievementProvider.STEAM

    /** Steam asks about the copy first; RetroAchievements matches by ROM hash. Others can't. */
    val canAutoMatch: Boolean
        get() = provider == AchievementProvider.STEAM || provider == AchievementProvider.RETRO_ACHIEVEMENTS

    /** Refresh is offered for an installed game with a provider identity to refresh against. */
    val canSync: Boolean get() = (linked || accountOnly) && installed

    val optionRows: List<CoinOptionRow> get() = coinOptionRows(this)

    val emptyMessage: String
        get() = when {
            query.isNotBlank() -> "No coins match \"${query.trim()}\"."
            filter == CoinFilter.EARNED -> "No coins earned yet."
            filter == CoinFilter.LOCKED -> "Every coin is earned."
            else -> "No coins to show."
        }
}

/**
 * The Options menu rows for [state], shaped like the library's: the root names each list with its
 * current choice, and a list checks the active one. Refresh this game is offered for an installed
 * game with a provider identity; Change Match only for a Steam library game, the one match the
 * user supplied.
 */
fun coinOptionRows(state: ShibaCoinsUiState): List<CoinOptionRow> = when (state.options?.group) {
    null -> buildList {
        add(CoinOptionRow("Sort (${state.sort.label})", CoinOption.OpenGroup(CoinOptionGroup.SORT)))
        // A sync in flight keeps its row so the menu doesn't reflow under the cursor; it just
        // says so and does nothing when picked.
        if (state.canSync) add(CoinOptionRow(if (state.isSyncing) "Refreshing…" else "Refresh this game", CoinOption.SyncNow))
        if (state.hasChangeMatch) add(CoinOptionRow("Change Match", CoinOption.ChangeMatch))
    }
    CoinOptionGroup.SORT -> CoinSort.entries.map { sort ->
        CoinOptionRow(sort.label, CoinOption.Sort(sort), checked = sort == state.sort)
    }
}

/**
 * The helper footer for [state]. Confirm is named for what it would do on the focused row and is
 * left out when it would do nothing; text entry and the Options menu replace the page hints.
 */
fun shibaCoinsHelperItems(state: ShibaCoinsUiState): List<ControllerPromptItem> = when {
    state.options != null -> listOf(
        ControllerPromptItem(GamepadAction.SELECT, "Select"),
        ControllerPromptItem(GamepadAction.BACK, "Close"),
    )
    state.searchEditing -> listOf(ControllerPromptItem(GamepadAction.BACK, "Done"))
    // The link panel is the whole page: there is nothing to search and no view to change.
    state.focused is CoinListItem.LinkPanel -> buildList {
        if (state.canAutoMatch) add(ControllerPromptItem(GamepadAction.SELECT, "Auto-Match"))
        add(ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Options"))
        add(ControllerPromptItem(GamepadAction.BACK, "Back"))
    }
    else -> buildList {
        val confirm = when {
            state.searchFocused -> "Type"
            // Confirm only does something on a coin the user may reveal or re-hide.
            state.focusedCoin?.isHideable == true -> if (state.focusedRevealed) "Hide" else "Reveal"
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
class ShibaCoinsViewModel @Inject constructor(
    private val gameRepository: GameRepository,
    private val achievementRepository: AchievementController,
    private val autoMatcher: AchievementAutoMatcher,
) : ViewModel() {

    private val _state = MutableStateFlow(ShibaCoinsUiState())
    val uiState: StateFlow<ShibaCoinsUiState> = _state.asStateFlow()

    private var gameId: Long = -1
    private var target: ShibaCoinsTarget = ShibaCoinsTarget.LibraryGame(-1)
    private val loadJobs = mutableListOf<Job>()

    fun load(target: ShibaCoinsTarget) {
        this.target = target
        // This ViewModel is retained across open/close, so clear the stale closed flag (otherwise
        // the screen's close-effect fires immediately on reopen) and start the page fresh: focus on
        // the pinned Search row, no query, no menu, and any revealed hidden coins re-hidden.
        _state.update {
            it.copy(
                closed = false,
                focusedRowId = null,
                query = "",
                searchEditing = false,
                options = null,
                revealedIds = emptySet(),
                autoMatchStep = null,
                isMatching = false,
                message = null,
            ).withRows()
        }
        loadJobs.forEach { it.cancel() }
        loadJobs.clear()
        when (target) {
            is ShibaCoinsTarget.LibraryGame -> loadLibraryGame(target.gameId)
            is ShibaCoinsTarget.AccountEntry -> loadAccountEntry(target)
        }
    }

    private fun loadLibraryGame(id: Long) {
        gameId = id
        _state.update { it.copy(accountOnly = false, installed = true) }
        loadJobs += viewModelScope.launch {
            val game = gameRepository.getById(id)
            val installed = game?.isMissing != true
            _state.update {
                it.copy(
                    title = game?.displayTitle ?: "",
                    platformLabel = game?.platformId?.let(::platformDisplay) ?: "",
                    provider = providerForPlatform(game?.platformId),
                    installed = installed,
                )
            }
            // Cached rows are already showing; at most one check if the detail is over a day
            // old. Never blocks the page and never runs for a game that isn't on this device.
            if (installed) runCatching { achievementRepository.refreshGameIfStale(id) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
        loadJobs += viewModelScope.launch {
            combine(
                achievementRepository.observeGameCoins(id),
                achievementRepository.observeCoins(id),
                achievementRepository.observeLink(id),
            ) { summary, coins, link ->
                Triple(summary, coins, link)
            }.collect { (summary, coins, link) ->
                _state.update {
                    it.copy(
                        summary = summary,
                        lastSyncedAt = summary?.lastSyncedAt,
                        coins = coins.map { e -> e.toRow() },
                        linked = link != null,
                        provider = link?.let { l -> AchievementProvider.fromName(l.provider) } ?: it.provider,
                        ownership = link?.ownership?.let(LocalCopyOwnership::fromName),
                    ).withRows()
                }
            }
        }
    }

    private fun loadAccountEntry(entry: ShibaCoinsTarget.AccountEntry) {
        gameId = -1
        _state.update {
            it.copy(
                accountOnly = true,
                linked = false,
                installed = false,
                provider = entry.provider,
                platformLabel = providerLabel(entry.provider),
            )
        }
        loadJobs += viewModelScope.launch {
            var staleChecked = false
            achievementRepository.observeIdentityStatus(entry.provider, entry.providerGameId).collect { status ->
                val installed = status?.isPresent == true
                _state.update {
                    it.copy(
                        installed = installed,
                        platformLabel = providerLabel(entry.provider) + if (installed) "" else " · Not installed",
                    )
                }
                // One stale-on-open check, and only for an entry that is on this device.
                if (installed && !staleChecked) {
                    staleChecked = true
                    runCatching { achievementRepository.refreshAccountEntryIfStale(entry.provider, entry.providerGameId) }
                        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                }
            }
        }
        loadJobs += viewModelScope.launch {
            combine(
                achievementRepository.observeAccountSet(entry.provider, entry.providerGameId),
                achievementRepository.observeAccountGameCoins(entry.provider, entry.providerGameId),
                achievementRepository.observeAccountCoins(entry.provider, entry.providerGameId),
            ) { set, summary, coins ->
                Triple(set, summary, coins)
            }.collect { (set, summary, coins) ->
                _state.update {
                    it.copy(
                        title = set?.title ?: "",
                        summary = summary,
                        lastSyncedAt = summary?.lastSyncedAt,
                        coins = coins.map { e -> e.toRow() },
                    ).withRows()
                }
            }
        }
    }

    fun close() = _state.update { it.copy(closed = true) }

    /** Clears the closed flag once the screen has acted on it, so the next open isn't cut short. */
    fun onClosedHandled() = _state.update { it.copy(closed = false) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ── Controller input ───────────────────────────────────────────────────────

    /** Controller input forwarded from the shell while this overlay is open. */
    fun handleGamepadAction(action: GamepadAction) {
        val s = _state.value
        // The Auto-Match prompts capture input while open: they are modal within the screen.
        when (s.autoMatchStep) {
            AutoMatchStep.CONFIRM_COPY -> {
                when (action) {
                    GamepadAction.NAVIGATE_LEFT, GamepadAction.NAVIGATE_RIGHT ->
                        _state.update { it.copy(autoMatchYes = !it.autoMatchYes) }
                    GamepadAction.SELECT -> chooseAutoMatch(s.autoMatchYes)
                    GamepadAction.BACK -> cancelAutoMatch()
                    else -> Unit
                }
                return
            }
            AutoMatchStep.ENTER_APPID -> {
                // Text entry is touch/IME-driven; the controller can only back out.
                if (action == GamepadAction.BACK) cancelAutoMatch()
                return
            }
            null -> Unit
        }
        // The notice line under Search is transient: the next press has read it.
        if (s.message != null) dismissMessage()
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
            // L / R change the view. LEFT / RIGHT stay as quiet aliases, as in the library.
            GamepadAction.PREV_CATEGORY, GamepadAction.NAVIGATE_LEFT -> cycleView(-1)
            GamepadAction.NEXT_CATEGORY, GamepadAction.NAVIGATE_RIGHT -> cycleView(1)
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
        if (s.message != null) dismissMessage()
        if (s.focusedRowId == rowId) activateFocused()
        else if (s.rows.any { it.id == rowId }) _state.update { it.copy(focusedRowId = rowId) }
    }

    /**
     * Confirm: Search starts typing, a hidden coin reveals (and re-hides), the link panel runs its
     * match. Platinum and an ordinary coin have nothing to open, so Confirm does nothing there.
     */
    private fun activateFocused() {
        when (val row = _state.value.focused) {
            null -> startSearchEdit()
            is CoinListItem.Coin -> toggleReveal(row.coin)
            is CoinListItem.LinkPanel -> onLinkPanelSelect()
            is CoinListItem.Platinum -> Unit
        }
    }

    /** Toggles a hidden coin between redacted and revealed. No-op for coins that aren't hidden. */
    fun toggleReveal(coin: CoinRow) {
        if (!coin.isHideable) return
        _state.update {
            val revealed = if (coin.id in it.revealedIds) it.revealedIds - coin.id else it.revealedIds + coin.id
            // A revealed coin becomes searchable by its real title, so the list is rebuilt.
            it.copy(revealedIds = revealed).withRows()
        }
    }

    // ── Search ─────────────────────────────────────────────────────────────────

    fun startSearchEdit() = _state.update { it.copy(focusedRowId = null, searchEditing = true) }

    /** Text entry ended (keyboard dismissed, IME action, or a pad press). The query is kept. */
    fun onSearchEditEnded() = _state.update { it.copy(searchEditing = false) }

    /** Touch: tapping Search goes straight to typing. */
    fun onSearchClick() = startSearchEdit()

    fun setQuery(query: String) = _state.update { it.copy(query = query).withRows() }

    // ── Views and sort ─────────────────────────────────────────────────────────

    fun setSort(sort: CoinSort) = _state.update { it.copy(sort = sort).withRows() }

    fun setFilter(filter: CoinFilter) = _state.update { it.copy(filter = filter).withRows() }

    private fun cycleView(dir: Int) {
        val views = CoinFilter.entries
        setFilter(views[(_state.value.filter.ordinal + dir).mod(views.size)])
    }

    // ── Options menu ───────────────────────────────────────────────────────────

    /** Opens the Options root on its first row. */
    fun openOptions() = _state.update { it.copy(options = CoinOptionsMenu(), searchEditing = false) }

    fun closeOptions() = _state.update { it.copy(options = null) }

    /** Swaps the menu to [group]'s list, with the cursor on its active choice. */
    private fun openOptionGroup(group: CoinOptionGroup) = _state.update { s ->
        val listed = s.copy(options = CoinOptionsMenu(group = group))
        val active = listed.optionRows.indexOfFirst { it.checked }.coerceAtLeast(0)
        listed.copy(options = CoinOptionsMenu(selectedIndex = active, group = group))
    }

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

    private fun moveOptionsCursor(menu: CoinOptionsMenu, delta: Int) = _state.update { s ->
        val last = (s.optionRows.size - 1).coerceAtLeast(0)
        s.copy(options = menu.copy(selectedIndex = (menu.selectedIndex + delta).coerceIn(0, last)))
    }

    /**
     * Activates an Options row (controller Confirm or tap), as the Icon Display menu does: a root
     * row opens its list, and a choice applies and closes the menu.
     */
    fun onOptionActivated(index: Int) {
        val row = _state.value.optionRows.getOrNull(index) ?: return
        when (val option = row.option) {
            is CoinOption.OpenGroup -> return openOptionGroup(option.group)
            is CoinOption.Sort -> setSort(option.sort)
            // A sync already in flight: the row says "Syncing…" and picking it holds the menu open.
            CoinOption.SyncNow -> if (_state.value.isSyncing) return else sync()
            CoinOption.ChangeMatch -> changeLink()
        }
        closeOptions()
    }

    // ── Rows ───────────────────────────────────────────────────────────────────

    /**
     * Rebuilds [ShibaCoinsUiState.displayed] and [ShibaCoinsUiState.rows] from the current set,
     * sort, view, query and reveals, then recovers focus the library's way: the same row if it is
     * still listed, otherwise the row now at the old position (clamped), otherwise Search.
     */
    private fun ShibaCoinsUiState.withRows(): ShibaCoinsUiState {
        val displayed = coins.arrange(sort, filter, query, revealedIds)
        val rows = buildList {
            if (showLinkPanel) {
                add(CoinListItem.LinkPanel)
                return@buildList
            }
            if (showsPlatinum()) {
                add(CoinListItem.Platinum(earned = earnedCoins, total = totalCoins, isMastered = isMastered))
            }
            displayed.forEach { add(CoinListItem.Coin(it)) }
        }
        val focusId = when {
            focusedRowId == null -> null
            rows.any { it.id == focusedRowId } -> focusedRowId
            rows.isEmpty() -> null
            else -> rows[(focusPosition - 1).coerceIn(0, rows.lastIndex)].id
        }
        return copy(
            displayed = displayed,
            rows = rows,
            viewCounts = CoinViewCounts(
                all = coins.size,
                earned = coins.count { it.isEarned },
                locked = coins.count { !it.isEarned },
            ),
            focusedRowId = focusId,
        )
    }

    // ── Linking, matching and syncing (carried over unchanged) ─────────────────

    /** Confirm on the link panel: Steam asks about the copy first, RetroAchievements hashes the ROM. */
    private fun onLinkPanelSelect() {
        when (_state.value.provider) {
            AchievementProvider.STEAM -> startAutoMatch()
            AchievementProvider.RETRO_ACHIEVEMENTS -> autoMatchRaByHash()
            // Local Steam links from the game folder and PS Vita from the Vita3K scan — the panel
            // says so, and there is nothing Confirm can do here.
            AchievementProvider.LOCAL_STEAM, AchievementProvider.VITA_TROPHY -> Unit
        }
    }

    /** Opens the Auto-Match flow: first ask whether this is a legitimate Steam copy. */
    fun startAutoMatch() = _state.update { it.copy(autoMatchStep = AutoMatchStep.CONFIRM_COPY, autoMatchYes = true) }

    /**
     * RetroAchievements Auto-Match: hash-only, so there is no copy question and no manual
     * fallback — hash the ROM, look it up, and either sync the fresh link or surface the
     * pipeline's reason (unreadable ROM, unsupported disc, hash not registered, list
     * unavailable) so the user knows what to fix.
     */
    fun autoMatchRaByHash() {
        if (_state.value.isMatching) return
        viewModelScope.launch {
            _state.update { it.copy(isMatching = true) }
            val result = autoMatcher.matchSingleByHash(gameId)
            _state.update { it.copy(isMatching = false) }
            when (result) {
                AchievementAutoMatcher.RaMatchResult.Matched -> sync()
                is AchievementAutoMatcher.RaMatchResult.Unmatched ->
                    _state.update { it.copy(message = result.reason) }
            }
        }
    }

    fun cancelAutoMatch() = _state.update { it.copy(autoMatchStep = null) }

    /**
     * Runs the branch the user picked: a legit copy resolves against Steam (embedded appid,
     * SteamGridDB, title) and falls through to manual appid entry when nothing matches. Any
     * other copy scans the windows game folders for Steam-emu data — no match there means the
     * game isn't set up for Local Steam yet, so instead of asking for an appid (which can't
     * help without the emu kit in the game folder) the user is pointed at the setup steps.
     */
    fun chooseAutoMatch(legit: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(autoMatchStep = null, isMatching = true) }
            if (legit) {
                val matched = autoMatcher.matchSingleAsSteam(gameId)
                _state.update { it.copy(isMatching = false) }
                if (matched) sync()
                else _state.update { it.copy(autoMatchStep = AutoMatchStep.ENTER_APPID) }
                return@launch
            }
            val result = autoMatcher.matchSingleAsLocalSteam(gameId)
            _state.update { it.copy(isMatching = false) }
            when (result) {
                AchievementAutoMatcher.LocalSteamMatchResult.Matched -> sync()
                AchievementAutoMatcher.LocalSteamMatchResult.NoEmuFolders -> _state.update {
                    it.copy(
                        message = "This game isn't set up for Local Steam yet. Enable Track Local " +
                            "Steam Games (Emulated) in Settings ▸ Shiba Coins, then scan your " +
                            "Windows games so its achievement kit is set up, and Auto-Match again.",
                    )
                }
                is AchievementAutoMatcher.LocalSteamMatchResult.NoNameMatch -> _state.update {
                    val folders = result.folderNames.take(3).joinToString(", ")
                    it.copy(
                        message = "Steam-emu data was found ($folders) but no folder matches this " +
                            "game's name. Rename the game to match its folder and Auto-Match again.",
                    )
                }
            }
        }
    }

    /**
     * Links the hand-entered appid under the branch's provider and validates it by syncing — a
     * failed sync unlinks again so a wrong id never leaves the game linked to nothing.
     */
    fun submitManualAppId(raw: String) {
        val id = raw.trim()
        if (id.isEmpty() || !id.all { ch -> ch.isDigit() }) {
            _state.update { it.copy(message = "A Steam app id is a number — check steamdb.info for more information on the game") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isMatching = true) }
            // Manual appid entry is only reachable from the legit-copy branch: the Local Steam
            // branch resolves from the emu folders (or explains what to set up) and never asks.
            achievementRepository.linkManually(gameId, AchievementProvider.STEAM, id)
            when (val result = achievementRepository.syncGameById(gameId)) {
                is ProviderSyncResult.Success ->
                    _state.update { it.copy(isMatching = false, autoMatchStep = null, message = null) }
                ProviderSyncResult.NotFound, is ProviderSyncResult.Failed -> {
                    achievementRepository.unlink(gameId)
                    _state.update {
                        it.copy(isMatching = false, message = "App id $id doesn't match — check steamdb.info for more information on the game")
                    }
                }
                // Credentials/profile problems aren't the appid's fault — keep the link, surface why.
                else -> _state.update { it.copy(isMatching = false, autoMatchStep = null, message = messageFor(result)) }
            }
        }
    }

    /** Removes the current link so the user can re-match it (edit a wrong match). */
    fun changeLink() {
        viewModelScope.launch { achievementRepository.unlink(gameId) }
    }

    fun sync() {
        viewModelScope.launch {
            _state.update { it.copy(isSyncing = true) }
            val result = when (val t = target) {
                is ShibaCoinsTarget.LibraryGame -> achievementRepository.syncGameById(t.gameId)
                is ShibaCoinsTarget.AccountEntry ->
                    achievementRepository.syncAccountEntry(t.provider, t.providerGameId, _state.value.title)
            }
            _state.update { it.copy(isSyncing = false, message = messageFor(result)) }
        }
    }

    private fun messageFor(result: ProviderSyncResult): String? = when (result) {
        is ProviderSyncResult.Success -> null
        ProviderSyncResult.NotLinked ->
            if (_state.value.installed) "Link this game to a provider id first"
            else "This game isn't installed — its saved achievements are shown"
        ProviderSyncResult.MissingCredentials -> "Add your key in Settings ▸ Shiba Coins"
        ProviderSyncResult.ProfileNotPublic -> "Your Steam profile's Game Details are private"
        ProviderSyncResult.NotFound -> "No achievements found for this game"
        is ProviderSyncResult.Failed -> "Sync failed: ${result.reason}"
    }

    private fun providerForPlatform(platformId: String?): AchievementProvider =
        if (platformId == "windows") AchievementProvider.STEAM else AchievementProvider.RETRO_ACHIEVEMENTS
}

// ── Pure list shaping ─────────────────────────────────────────────────────────

/** Individual coins earned (the Platinum is the prize for taking them all, never one of them). */
internal val ShibaCoinsUiState.earnedCoins: Int get() = summary?.earned?.total ?: 0

internal val ShibaCoinsUiState.totalCoins: Int get() = summary?.total?.total ?: 0

internal val ShibaCoinsUiState.isMastered: Boolean get() = summary?.isMastered == true

/**
 * Whether the Platinum Crown row belongs in the current view: it is a locked award until the set is
 * mastered and an earned one after, so it follows the view the same way a coin does. A game with no
 * synced set has no crown to show, and a search only reaches it by name.
 */
internal fun ShibaCoinsUiState.showsPlatinum(): Boolean {
    if (summary == null) return false
    val needle = query.trim()
    if (needle.isNotEmpty() && !PLATINUM_SEARCH_TEXT.contains(needle, ignoreCase = true)) return false
    return when (filter) {
        CoinFilter.ALL -> true
        CoinFilter.EARNED -> isMastered
        CoinFilter.LOCKED -> !isMastered
    }
}

/** What the Platinum Crown row matches on — its own title, so "platinum" or "crown" finds it. */
private const val PLATINUM_SEARCH_TEXT = "Platinum Crown"

/**
 * Pure sort + view + search for the coin list — used by the screen and unit-tested directly.
 *
 * [revealedIds] matters because a redacted hidden coin must never be findable by the words it is
 * hiding: until the user reveals it, it matches only what the row actually shows.
 */
fun List<CoinRow>.arrange(
    sort: CoinSort,
    filter: CoinFilter,
    query: String = "",
    revealedIds: Set<String> = emptySet(),
): List<CoinRow> {
    val viewed = when (filter) {
        CoinFilter.ALL -> this
        CoinFilter.EARNED -> filter { it.isEarned }
        CoinFilter.LOCKED -> filter { !it.isEarned }
    }
    val needle = query.trim()
    val found = if (needle.isEmpty()) viewed else viewed.filter { it.matches(needle, revealedIds) }
    return when (sort) {
        // Lowest tier first (Bronze at the top, up to Gold), then rarest within the tier.
        CoinSort.TIER -> found.sortedWith(compareBy({ tierRank(it.tier) }, { it.rarityRank }))
        CoinSort.EARNED -> found.sortedWith(compareByDescending<CoinRow> { it.isEarned }.thenByDescending { it.earnedAt ?: 0L })
        CoinSort.RAREST -> found.sortedBy { it.rarityRank }
    }
}

private fun CoinRow.matches(needle: String, revealedIds: Set<String>): Boolean =
    if (isHideable && id !in revealedIds) {
        // Redacted: only the placeholder the row draws is searchable.
        REDACTED_TITLE.contains(needle, ignoreCase = true)
    } else {
        title.contains(needle, ignoreCase = true) || description.contains(needle, ignoreCase = true)
    }

// Coins whose provider reported no rarity (stored as a negative sentinel) sort after every real
// percentage — unknown rarity can't rank as rarest.
private val CoinRow.rarityRank: Double get() = if (globalRarity < 0) Double.MAX_VALUE else globalRarity

private fun tierRank(tier: ShibaTier): Int = when (tier) {
    ShibaTier.BRONZE -> 0
    ShibaTier.SILVER -> 1
    ShibaTier.GOLD -> 2
    ShibaTier.PLATINUM -> 3
}

/** The provider's display name, shown where a library game would show its platform. */
internal fun providerLabel(provider: AchievementProvider): String = when (provider) {
    AchievementProvider.RETRO_ACHIEVEMENTS -> "RetroAchievements"
    AchievementProvider.STEAM -> "Steam"
    AchievementProvider.LOCAL_STEAM -> "Local Steam"
    AchievementProvider.VITA_TROPHY -> "PS Vita"
}

private fun AccountAchievementEntity.toRow() = CoinRow(
    id = providerAchievementId,
    tier = runCatching { ShibaTier.valueOf(tier) }.getOrDefault(ShibaTier.BRONZE),
    title = title,
    description = description,
    globalRarity = globalRarity,
    iconUrl = iconUrl,
    isHidden = isHidden,
    isEarned = isEarned,
    earnedAt = earnedAt,
)
