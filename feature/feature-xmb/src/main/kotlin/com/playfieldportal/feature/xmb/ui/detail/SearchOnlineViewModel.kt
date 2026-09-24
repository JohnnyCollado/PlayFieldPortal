package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.network.NetworkMonitor
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.SyncedCoin
import com.playfieldportal.feature.achievements.match.RaConsole
import com.playfieldportal.feature.achievements.match.RaConsoleOption
import com.playfieldportal.feature.achievements.preview.AchievementPreviewRepository
import com.playfieldportal.feature.achievements.preview.PreviewCandidate
import com.playfieldportal.feature.achievements.preview.PreviewSearch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Search online ─────────────────────────────────────────────────────────────
//
// Plan Task 8 (docs/plans/PFP_Local_Achievements_Selective_Sync_Implementation_Plan.md): the
// explicit provider search, reached from the Tracked/Untracked browser's pinned "Search online"
// row. It looks a game up on Steam or RetroAchievements and shows its achievements as a PREVIEW —
// the game is not on this device, so nothing here is tracked, counted, queued or written. That
// guarantee is structural: this view model's only data source is [AchievementPreviewRepository],
// which holds no DAO, ledger, writer or coordinator, and it never touches AchievementController.
//
// The page keeps the achievement pages' contract: a pinned Search row at navigation position 0,
// stable-id focus, L/R across the preview's All / Earned / Locked views, and a modal Triangle menu
// (here: the provider list, and RetroAchievements' console list).

/** The two providers an explicit search can reach. Local Steam and Vita are device-only. */
enum class SearchProvider(val provider: AchievementProvider, val label: String) {
    STEAM(AchievementProvider.STEAM, "Steam"),
    RETRO_ACHIEVEMENTS(AchievementProvider.RETRO_ACHIEVEMENTS, "RetroAchievements"),
}

/** What the list area is saying while nothing is listed. */
enum class SearchStatus {
    /** Fewer than [AchievementPreviewRepository.MIN_QUERY_LENGTH] characters typed. */
    IDLE,
    SEARCHING,
    RESULTS,
    NO_RESULTS,
    OFFLINE,

    /** RetroAchievements only: no username / API key saved, so its catalog can't be read. */
    NOT_CONNECTED,
    FAILED,
}

/** A row of either list: a search result, or one coin of the open preview. */
@Immutable
sealed interface SearchOnlineRow {
    val id: String

    data class Result(val candidate: PreviewCandidate) : SearchOnlineRow {
        override val id: String get() = "${candidate.provider.name}:${candidate.providerGameId}"
    }

    data class Coin(val coin: CoinRow) : SearchOnlineRow {
        override val id: String get() = coin.id
    }
}

/** The open preview: a read-only provider fetch, discarded when it closes. */
@Immutable
data class PreviewUi(
    val candidate: PreviewCandidate,
    val loading: Boolean = false,
    val coins: List<CoinRow> = emptyList(),
    /** Set when the fetch could not produce a set; the list area shows it instead of coins. */
    val error: String? = null,
    val filter: CoinFilter = CoinFilter.ALL,
    val query: String = "",
    val searchEditing: Boolean = false,
    val focusedCoinId: String? = null,
    /** Hidden coins the user chose to reveal, as on a game's own page. Lives and dies with the preview. */
    val revealedIds: Set<String> = emptySet(),
) {
    val counts: CoinViewCounts
        get() = CoinViewCounts(
            all = coins.size,
            earned = coins.count { it.isEarned },
            locked = coins.count { !it.isEarned },
        )

    /** The header's "AVAILABLE · 51 achievements". */
    val available: Int get() = coins.size

    val rows: List<SearchOnlineRow>
        get() {
            val needle = query.trim()
            return coins.asSequence()
                .filter {
                    when (filter) {
                        CoinFilter.ALL -> true
                        CoinFilter.EARNED -> it.isEarned
                        CoinFilter.LOCKED -> !it.isEarned
                    }
                }
                .filter { needle.isEmpty() || it.title.contains(needle, ignoreCase = true) }
                .map { SearchOnlineRow.Coin(it) }
                .toList()
        }
}

/** A second-level list in the Options menu, opened from its root row (the Icon Display pattern). */
enum class SearchOptionGroup(val title: String) { PROVIDER("Provider"), CONSOLE("System") }

/** What an Options row does. */
sealed interface SearchOption {
    data class OpenGroup(val group: SearchOptionGroup) : SearchOption
    data class Provider(val provider: SearchProvider) : SearchOption
    data class Console(val console: RaConsoleOption) : SearchOption
    data object RefreshPreview : SearchOption
}

/** A row of the Options menu: its label, action, and whether it is the active choice. */
data class SearchOptionRow(
    val label: String,
    val option: SearchOption,
    val checked: Boolean = false,
)

/** The open Options menu; its cursor is separate from the list's, which it leaves untouched. */
data class SearchOptionsMenu(
    val selectedIndex: Int = 0,
    val group: SearchOptionGroup? = null,
) {
    val title: String get() = group?.title ?: "Options"
}

@Immutable
data class SearchOnlineUiState(
    val provider: SearchProvider = SearchProvider.STEAM,
    /** RetroAchievements searches one console at a time; Steam ignores this. */
    val console: RaConsoleOption = RaConsole.searchable.first(),
    val query: String = "",
    val searchEditing: Boolean = false,
    val status: SearchStatus = SearchStatus.IDLE,
    val results: List<PreviewCandidate> = emptyList(),
    val focusedResultId: String? = null,
    val preview: PreviewUi? = null,
    val options: SearchOptionsMenu? = null,
    val closed: Boolean = false,
    /** Set when the user asks for the provider's Settings page; the shell opens it and clears this. */
    val openCredentials: Boolean = false,
) {
    val inPreview: Boolean get() = preview != null

    val rows: List<SearchOnlineRow>
        get() = preview?.rows ?: results.map { SearchOnlineRow.Result(it) }

    /** The focused row's id, or null when the pinned Search row (position 0) has focus. */
    val focusedRowId: String? get() = preview?.focusedCoinId ?: focusedResultId

    /** Navigation position: 0 is Search, 1 is the first row. */
    val focusPosition: Int
        get() = focusedRowId?.let { id -> rows.indexOfFirst { it.id == id } + 1 } ?: 0

    val focused: SearchOnlineRow? get() = rows.getOrNull(focusPosition - 1)
    val searchFocused: Boolean get() = focused == null

    /** Hidden coins revealed in the open preview. */
    val revealedIds: Set<String> get() = preview?.revealedIds ?: emptySet()

    /** Text entry and the query belong to whichever list is on screen. */
    val searchEditingNow: Boolean get() = preview?.searchEditing ?: searchEditing
    val queryNow: String get() = preview?.query ?: query

    val title: String get() = preview?.candidate?.title ?: "Search online"

    /** The header's second line. */
    val subtitle: String
        get() = when {
            preview != null -> "${preview.candidate.platformLabel} · Not installed"
            status == SearchStatus.RESULTS -> "${providerLabel()} · ${resultCount(results.size)}"
            else -> providerLabel()
        }

    /** The provider, plus the console a RetroAchievements search is pointed at. */
    fun providerLabel(): String = when (provider) {
        SearchProvider.STEAM -> SearchProvider.STEAM.label
        SearchProvider.RETRO_ACHIEVEMENTS -> "${SearchProvider.RETRO_ACHIEVEMENTS.label} · ${console.label}"
    }

    val searchPlaceholder: String
        get() = if (preview != null) "Search coins…" else "Search ${provider.label}…"

    /** What the list area says when it has no rows to show. */
    val emptyMessage: String
        get() {
            val open = preview
            if (open != null) {
                return when {
                    open.loading -> "Loading achievements…"
                    open.error != null -> open.error
                    open.coins.isEmpty() -> "${open.candidate.title} has no achievements on ${provider.label}."
                    open.query.isNotBlank() -> "No coins match \"${open.query.trim()}\"."
                    else -> "No coins in this view."
                }
            }
            return when (status) {
                SearchStatus.IDLE ->
                    "Type at least ${AchievementPreviewRepository.MIN_QUERY_LENGTH} letters to search ${provider.label}."
                SearchStatus.SEARCHING -> "Searching ${provider.label}…"
                SearchStatus.NO_RESULTS -> "No ${provider.label} games match \"${query.trim()}\"."
                SearchStatus.OFFLINE -> "Searching needs an internet connection."
                SearchStatus.NOT_CONNECTED -> "Connect ${provider.label} to search its games."
                SearchStatus.FAILED -> "${provider.label} couldn't be reached. Try again."
                SearchStatus.RESULTS -> ""
            }
        }

    /** The states that offer a button: retry, or open the provider's Settings page. */
    val recovery: SearchRecovery?
        get() = when {
            preview != null -> null
            status == SearchStatus.OFFLINE || status == SearchStatus.FAILED -> SearchRecovery.RETRY
            status == SearchStatus.NOT_CONNECTED -> SearchRecovery.OPEN_SETTINGS
            else -> null
        }

    val optionRows: List<SearchOptionRow> get() = searchOptionRows(this)
}

/** The one action an error state offers. */
enum class SearchRecovery(val label: String) { RETRY("Try again"), OPEN_SETTINGS("Open Settings") }

private fun resultCount(count: Int): String = if (count == 1) "1 result" else "$count results"

/**
 * The Options menu rows for [state], shaped like the library's: the root names each list with its
 * current choice, and a list checks the active one. The console list is offered for
 * RetroAchievements only; an open preview has nothing to switch, so it offers a refresh instead.
 */
fun searchOptionRows(state: SearchOnlineUiState): List<SearchOptionRow> = when (state.options?.group) {
    null -> buildList {
        if (state.inPreview) {
            add(SearchOptionRow("Refresh preview", SearchOption.RefreshPreview))
            return@buildList
        }
        add(
            SearchOptionRow(
                label = "Provider (${state.provider.label})",
                option = SearchOption.OpenGroup(SearchOptionGroup.PROVIDER),
            ),
        )
        if (state.provider == SearchProvider.RETRO_ACHIEVEMENTS) {
            add(
                SearchOptionRow(
                    label = "System (${state.console.label})",
                    option = SearchOption.OpenGroup(SearchOptionGroup.CONSOLE),
                ),
            )
        }
    }
    SearchOptionGroup.PROVIDER -> SearchProvider.entries.map {
        SearchOptionRow(it.label, SearchOption.Provider(it), checked = it == state.provider)
    }
    SearchOptionGroup.CONSOLE -> RaConsole.searchable.map {
        SearchOptionRow(it.label, SearchOption.Console(it), checked = it.consoleId == state.console.consoleId)
    }
}

/**
 * The helper footer for [state]: Confirm is named for what it would do on the focused element and
 * is left out when it would do nothing; text entry and the Options menu replace the page hints.
 */
fun searchOnlineHelperItems(state: SearchOnlineUiState): List<ControllerPromptItem> = when {
    state.options != null -> listOf(
        ControllerPromptItem(GamepadAction.SELECT, "Select"),
        ControllerPromptItem(GamepadAction.BACK, "Close"),
    )
    state.searchEditingNow -> listOf(ControllerPromptItem(GamepadAction.BACK, "Done"))
    state.inPreview -> buildList {
        // Confirm only does something on a hidden coin, so it is named only there.
        val coin = (state.focused as? SearchOnlineRow.Coin)?.coin
        if (coin != null && coin.isHideable) {
            add(ControllerPromptItem(GamepadAction.SELECT, if (coin.id in state.revealedIds) "Hide" else "Reveal"))
        }
        add(ControllerPromptItem(GamepadAction.CHANGE_SORT, "Search"))
        add(ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Options"))
        add(ControllerPromptItem(listOf(GamepadAction.PREV_CATEGORY, GamepadAction.NEXT_CATEGORY), "Change View"))
        add(ControllerPromptItem(GamepadAction.BACK, "Close preview"))
    }
    else -> listOf(
        ControllerPromptItem(GamepadAction.SELECT, if (state.searchFocused) "Type" else "Preview"),
        ControllerPromptItem(GamepadAction.CHANGE_SORT, "Search"),
        ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Change Provider"),
        ControllerPromptItem(GamepadAction.BACK, "Back"),
    )
}

@HiltViewModel
class SearchOnlineViewModel @Inject constructor(
    private val previews: AchievementPreviewRepository,
    private val credentials: AchievementCredentialsProvider,
    private val network: NetworkMonitor,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchOnlineUiState())
    val uiState: StateFlow<SearchOnlineUiState> = _state.asStateFlow()

    /** The in-flight search; a new keystroke, a provider change or a retry cancels it. */
    private var searchJob: Job? = null

    /** The in-flight preview fetch. */
    private var previewJob: Job? = null

    /** Leaving the page discards an open preview with it: nothing about it outlives the screen. */
    fun close() {
        closePreview()
        _state.update { it.copy(closed = true) }
    }
    fun onClosedHandled() = _state.update { it.copy(closed = false) }
    fun onCredentialsHandled() = _state.update { it.copy(openCredentials = false) }

    /** Controller input forwarded from the shell while this screen is open. */
    fun handleGamepadAction(action: GamepadAction) {
        val s = _state.value
        if (s.options != null) {
            handleOptionsAction(action)
            return
        }
        if (s.searchEditingNow) {
            // An open keyboard receives key events before the shell does, so a pad press arriving
            // here means the keyboard is already gone: end text entry. Back and Confirm stop there;
            // anything else also does its normal job.
            onSearchEditEnded()
            if (action == GamepadAction.BACK || action == GamepadAction.SELECT) return
        }
        when (action) {
            GamepadAction.NAVIGATE_UP -> moveFocus(-1)
            GamepadAction.NAVIGATE_DOWN -> moveFocus(1)
            // L / R cycle the preview's views. On the result list there is nothing to cycle.
            GamepadAction.PREV_CATEGORY, GamepadAction.NAVIGATE_LEFT -> cycleFilter(-1)
            GamepadAction.NEXT_CATEGORY, GamepadAction.NAVIGATE_RIGHT -> cycleFilter(1)
            GamepadAction.SELECT -> activateFocused()
            // Square always means "go to Search"; pressed on Search itself it starts typing.
            GamepadAction.CHANGE_SORT -> if (_state.value.searchFocused) startSearchEdit() else focusSearch()
            GamepadAction.OPEN_CONTEXT_MENU -> openOptions()
            // Back leaves the preview first: the page itself only closes from the result list.
            GamepadAction.BACK -> if (_state.value.inPreview) closePreview() else close()
            GamepadAction.HOME -> Unit
        }
    }

    // ── Focus ──────────────────────────────────────────────────────────────────

    private fun moveFocus(delta: Int) = _state.update { s ->
        val position = (s.focusPosition + delta).coerceIn(0, s.rows.size)
        s.withFocus(s.rows.getOrNull(position - 1)?.id)
    }

    fun focusSearch() = _state.update { it.withFocus(null) }

    // Focus lives with whichever list is on screen, so leaving a preview restores the result cursor.
    private fun SearchOnlineUiState.withFocus(rowId: String?): SearchOnlineUiState =
        if (preview != null) copy(preview = preview.copy(focusedCoinId = rowId)) else copy(focusedResultId = rowId)

    /** Touch: the first tap on a row focuses it; tapping the focused row activates it. */
    fun onRowClick(rowId: String) {
        val s = _state.value
        if (s.searchEditingNow) onSearchEditEnded()
        if (s.focusedRowId == rowId) activateFocused()
        else if (s.rows.any { it.id == rowId }) _state.update { it.withFocus(rowId) }
    }

    /** Confirm: Search starts typing, a result opens its preview, a hidden coin reveals itself. */
    private fun activateFocused() {
        when (val row = _state.value.focused) {
            null -> startSearchEdit()
            is SearchOnlineRow.Result -> openPreview(row.candidate)
            is SearchOnlineRow.Coin -> toggleReveal(row.coin)
        }
    }

    // A hidden coin the user has not earned can be revealed and re-hidden, as on a game's own page.
    private fun toggleReveal(coin: CoinRow) = _state.update { s ->
        val open = s.preview ?: return@update s
        if (!coin.isHideable) return@update s
        val revealed = if (coin.id in open.revealedIds) open.revealedIds - coin.id else open.revealedIds + coin.id
        s.copy(preview = open.copy(revealedIds = revealed))
    }

    // ── Search ─────────────────────────────────────────────────────────────────

    /** Touch: tapping Search goes straight to typing. */
    fun onSearchClick() = startSearchEdit()

    fun startSearchEdit() = _state.update {
        if (it.preview != null) it.copy(preview = it.preview.copy(focusedCoinId = null, searchEditing = true))
        else it.copy(focusedResultId = null, searchEditing = true)
    }

    /** Text entry ended (keyboard dismissed, IME action, or a pad press). The query is kept. */
    fun onSearchEditEnded() = _state.update {
        if (it.preview != null) it.copy(preview = it.preview.copy(searchEditing = false))
        else it.copy(searchEditing = false)
    }

    fun setQuery(query: String) {
        val open = _state.value.preview
        if (open != null) {
            // The preview's own search is local: it filters the fetched set, nothing is requested.
            _state.update { it.copy(preview = open.copy(query = query, focusedCoinId = null)) }
            return
        }
        _state.update { it.copy(query = query) }
        scheduleSearch(query)
    }

    /** The offline / failure state's Try again, and what a provider or console change re-runs. */
    fun retrySearch() = scheduleSearch(_state.value.query, debounce = false)

    /** The not-connected state's Open Settings. */
    fun requestCredentials() = _state.update { it.copy(openCredentials = true) }

    private fun scheduleSearch(query: String, debounce: Boolean = true) {
        searchJob?.cancel()
        val needle = query.trim()
        if (needle.length < AchievementPreviewRepository.MIN_QUERY_LENGTH) {
            _state.update { it.copy(status = SearchStatus.IDLE, results = emptyList(), focusedResultId = null) }
            return
        }
        _state.update { it.copy(status = SearchStatus.SEARCHING) }
        searchJob = viewModelScope.launch {
            // One request per pause in typing, never one per keystroke.
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            val s = _state.value
            when (s.provider) {
                SearchProvider.STEAM -> applyResults(previews.searchSteam(needle), unavailable = false)
                SearchProvider.RETRO_ACHIEVEMENTS ->
                    when (val result = previews.searchRetroAchievements(s.console.consoleId, needle)) {
                        is PreviewSearch.Results -> applyResults(result.candidates, unavailable = false)
                        PreviewSearch.Unavailable -> applyResults(emptyList(), unavailable = true)
                    }
            }
        }
    }

    /**
     * Turns a finished search into a state. An empty list is only "no results" when the device is
     * online and the provider is connected — otherwise it is the actionable condition that caused
     * it, so the page never reports an unreachable provider as a game that does not exist.
     */
    private suspend fun applyResults(candidates: List<PreviewCandidate>, unavailable: Boolean) {
        if (candidates.isNotEmpty()) {
            _state.update {
                it.copy(
                    status = SearchStatus.RESULTS,
                    results = candidates,
                    focusedResultId = SearchOnlineRow.Result(candidates.first()).id,
                )
            }
            return
        }
        val status = when {
            !network.isOnline() -> SearchStatus.OFFLINE
            unavailable && !raConnected() -> SearchStatus.NOT_CONNECTED
            unavailable -> SearchStatus.FAILED
            else -> SearchStatus.NO_RESULTS
        }
        _state.update { it.copy(status = status, results = emptyList(), focusedResultId = null) }
    }

    private suspend fun raConnected(): Boolean =
        !credentials.raUsername().isNullOrBlank() && !credentials.raApiKey().isNullOrBlank()

    // ── Preview ────────────────────────────────────────────────────────────────

    private fun openPreview(candidate: PreviewCandidate) {
        previewJob?.cancel()
        _state.update { it.copy(preview = PreviewUi(candidate = candidate, loading = true), options = null) }
        previewJob = viewModelScope.launch { fetchPreview(candidate) }
    }

    /** Options → Refresh preview: the same read-only fetch, after discarding the held result. */
    fun refreshPreview() {
        val candidate = _state.value.preview?.candidate ?: return
        previewJob?.cancel()
        _state.update { it.copy(preview = PreviewUi(candidate = candidate, loading = true)) }
        previewJob = viewModelScope.launch {
            previews.close(candidate)
            fetchPreview(candidate)
        }
    }

    private suspend fun fetchPreview(candidate: PreviewCandidate) {
        val result = previews.open(candidate)
        _state.update { s ->
            // A different preview (or the page's close) may have won the race while this fetch ran.
            val open = s.preview?.takeIf { it.candidate == candidate } ?: return@update s
            when (result) {
                is ProviderSyncResult.Success ->
                    s.copy(
                        preview = open.copy(
                            loading = false,
                            coins = result.coins.map { it.toPreviewRow() },
                            error = null,
                            focusedCoinId = null,
                        ),
                    )
                else -> s.copy(preview = open.copy(loading = false, coins = emptyList(), error = result.message()))
            }
        }
    }

    /** Back from a preview: discard it (the repository forgets it) and return to the results. */
    fun closePreview() {
        val candidate = _state.value.preview?.candidate ?: return
        previewJob?.cancel()
        _state.update { it.copy(preview = null, options = null) }
        viewModelScope.launch { previews.close(candidate) }
    }

    fun setFilter(filter: CoinFilter) = _state.update {
        val open = it.preview ?: return@update it
        it.copy(preview = open.copy(filter = filter, focusedCoinId = null))
    }

    private fun cycleFilter(dir: Int) {
        val open = _state.value.preview ?: return
        val views = CoinFilter.entries
        setFilter(views[(open.filter.ordinal + dir).mod(views.size)])
    }

    // ── Options menu ───────────────────────────────────────────────────────────

    /** Opens the Options root on its first row. */
    fun openOptions() = _state.update { it.copy(options = SearchOptionsMenu(), searchEditing = false) }

    fun closeOptions() = _state.update { it.copy(options = null) }

    /** Swaps the menu to [group]'s list, with the cursor on its active choice. */
    private fun openOptionGroup(group: SearchOptionGroup) = _state.update { s ->
        val listed = s.copy(options = SearchOptionsMenu(group = group))
        val active = listed.optionRows.indexOfFirst { it.checked }.coerceAtLeast(0)
        listed.copy(options = SearchOptionsMenu(selectedIndex = active, group = group))
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

    private fun moveOptionsCursor(menu: SearchOptionsMenu, delta: Int) = _state.update { s ->
        val last = (s.optionRows.size - 1).coerceAtLeast(0)
        s.copy(options = menu.copy(selectedIndex = (menu.selectedIndex + delta).coerceIn(0, last)))
    }

    /** Activates an Options row (controller Confirm or tap): a root row opens its list. */
    fun onOptionActivated(index: Int) {
        val row = _state.value.optionRows.getOrNull(index) ?: return
        when (val option = row.option) {
            is SearchOption.OpenGroup -> return openOptionGroup(option.group)
            is SearchOption.Provider -> setProvider(option.provider)
            is SearchOption.Console -> setConsole(option.console)
            SearchOption.RefreshPreview -> refreshPreview()
        }
        closeOptions()
    }

    fun setProvider(provider: SearchProvider) {
        if (provider == _state.value.provider) return
        _state.update { it.copy(provider = provider, results = emptyList(), focusedResultId = null) }
        retrySearch()
    }

    fun setConsole(console: RaConsoleOption) {
        if (console.consoleId == _state.value.console.consoleId) return
        _state.update { it.copy(console = console, results = emptyList(), focusedResultId = null) }
        retrySearch()
    }

    companion object {
        /** One request per pause in typing. */
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}

/** A previewed coin reads like any other; only the page around it says it is not tracked. */
private fun SyncedCoin.toPreviewRow() = CoinRow(
    id = providerAchievementId,
    tier = tier,
    title = title,
    description = description,
    globalRarity = globalRarity,
    iconUrl = iconUrl,
    isHidden = isHidden,
    isEarned = isEarned,
    earnedAt = earnedAt,
)

/** Why a preview could not be shown, in the page's own words — never a key or a URL. */
private fun ProviderSyncResult.message(): String = when (this) {
    is ProviderSyncResult.Success -> ""
    ProviderSyncResult.MissingCredentials -> "Connect this provider in Settings to look its games up."
    ProviderSyncResult.NotLinked -> "This game has no achievement set to show."
    ProviderSyncResult.ProfileNotPublic -> "Steam Game Details aren't available for your profile."
    ProviderSyncResult.NotFound -> "This game has no achievements on this provider."
    is ProviderSyncResult.Failed -> reason
}
