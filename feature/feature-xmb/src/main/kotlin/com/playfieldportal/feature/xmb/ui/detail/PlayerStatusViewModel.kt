package com.playfieldportal.feature.xmb.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.EarnedCoinRef
import com.playfieldportal.core.domain.achievement.LibraryStanding
import com.playfieldportal.core.domain.achievement.RecentCoin
import com.playfieldportal.core.domain.achievement.ShibaLevel
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.feature.achievements.AchievementController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One recently earned achievement, as the status feed renders it. */
data class RecentRow(
    val id: String,
    val coinTitle: String,
    val gameTitle: String,
    val tier: ShibaTier,
    val iconUrl: String?,
    val earnedAt: Long,
    val globalRarity: Double,
    val provider: AchievementProvider?,
    val coinsTarget: ShibaCoinsTarget?,
)

data class RarestCard(
    val id: String = "player-status:rarest",
    val coinTitle: String,
    val gameTitle: String,
    val tier: ShibaTier,
    val globalRarity: Double,
    val iconUrl: String?,
    val coinsTarget: ShibaCoinsTarget?,
)

enum class PlayerStatusSort(val label: String) {
    NEWEST("Newest"),
    RAREST("Rarest"),
}

enum class PlayerStatusProviderFilter(val label: String, val provider: AchievementProvider?) {
    ALL("All", null),
    RETRO("RetroAchievements", AchievementProvider.RETRO_ACHIEVEMENTS),
    STEAM("Steam", AchievementProvider.STEAM),
    LOCAL_STEAM("Local Steam", AchievementProvider.LOCAL_STEAM),
    VITA("PS Vita", AchievementProvider.VITA_TROPHY),
}

enum class PlayerStatusOptionGroup(val title: String) { SORT("Sort"), PROVIDER("Provider") }

sealed interface PlayerStatusOption {
    data class OpenGroup(val group: PlayerStatusOptionGroup) : PlayerStatusOption
    data class Sort(val sort: PlayerStatusSort) : PlayerStatusOption
    data class Provider(val filter: PlayerStatusProviderFilter) : PlayerStatusOption
    data object SyncAll : PlayerStatusOption
}

data class PlayerStatusOptionRow(
    val label: String,
    val option: PlayerStatusOption,
    val checked: Boolean = false,
)

data class PlayerStatusOptionsMenu(
    val selectedIndex: Int = 0,
    val group: PlayerStatusOptionGroup? = null,
) {
    val title: String get() = group?.title ?: "Options"
}

data class PlayerStatusUiState(
    val rankLabel: String = "",
    val level: Int = 1,
    val bones: Int = 0,
    val totalXp: Int = 0,
    val xpIntoLevel: Int = 0,
    val xpForNextLevel: Int = 0,
    val nextIsBone: Boolean = false,
    val bronze: Int = 0,
    val silver: Int = 0,
    val gold: Int = 0,
    val platinum: Int = 0,
    val coinsEarned: Int = 0,
    val coinsAvailable: Int = 0,
    val gamesTracked: Int = 0,
    val gamesMastered: Int = 0,
    val recent: List<RecentRow> = emptyList(),
    val allRecent: List<RecentRow> = emptyList(),
    val rarest: RarestCard? = null,
    val sort: PlayerStatusSort = PlayerStatusSort.NEWEST,
    val providerFilter: PlayerStatusProviderFilter = PlayerStatusProviderFilter.ALL,
    val focusedId: String? = null,
    val onRarest: Boolean = false,
    val options: PlayerStatusOptionsMenu? = null,
    val isSyncing: Boolean = false,
    val message: String? = null,
    val closed: Boolean = false,
    val openCoins: ShibaCoinsTarget? = null,
) {
    val xpToNext: Int get() = (xpForNextLevel - xpIntoLevel).coerceAtLeast(0)
    val levelFraction: Float
        get() = if (xpForNextLevel <= 0) 0f else (xpIntoLevel.toFloat() / xpForNextLevel).coerceIn(0f, 1f)
    val recentFocused: Boolean get() = !onRarest && focusedId != null
    val rarestFocused: Boolean get() = onRarest
    val focusedRecent: RecentRow? get() = recent.firstOrNull { it.id == focusedId }
    val optionRows: List<PlayerStatusOptionRow> get() = playerStatusOptionRows(this)
}

fun playerStatusOptionRows(state: PlayerStatusUiState): List<PlayerStatusOptionRow> = when (state.options?.group) {
    null -> listOf(
        PlayerStatusOptionRow("Sort (${state.sort.label})", PlayerStatusOption.OpenGroup(PlayerStatusOptionGroup.SORT)),
        PlayerStatusOptionRow("Provider (${state.providerFilter.label})", PlayerStatusOption.OpenGroup(PlayerStatusOptionGroup.PROVIDER)),
        PlayerStatusOptionRow(if (state.isSyncing) "Updating…" else "Update Installed Achievements", PlayerStatusOption.SyncAll),
    )
    PlayerStatusOptionGroup.SORT -> PlayerStatusSort.entries.map {
        PlayerStatusOptionRow(it.label, PlayerStatusOption.Sort(it), checked = it == state.sort)
    }
    PlayerStatusOptionGroup.PROVIDER -> PlayerStatusProviderFilter.entries.map {
        PlayerStatusOptionRow(it.label, PlayerStatusOption.Provider(it), checked = it == state.providerFilter)
    }
}

fun playerStatusHelperItems(state: PlayerStatusUiState): List<ControllerPromptItem> = when {
    state.options != null -> listOf(
        ControllerPromptItem(GamepadAction.SELECT, "Select"),
        ControllerPromptItem(GamepadAction.BACK, "Close"),
    )
    else -> buildList {
        if (state.focusedRecent?.coinsTarget != null || state.rarestFocused && state.rarest?.coinsTarget != null) {
            add(ControllerPromptItem(GamepadAction.SELECT, "View Game"))
        }
        add(ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, "Options"))
        add(ControllerPromptItem(GamepadAction.BACK, "Back"))
    }
}

private fun visibleRows(
    rows: List<RecentRow>,
    sort: PlayerStatusSort,
    provider: PlayerStatusProviderFilter,
): List<RecentRow> = rows
    .filter { provider.provider == null || it.provider == provider.provider }
    .sortedWith(
        when (sort) {
            PlayerStatusSort.NEWEST -> compareByDescending<RecentRow> { it.earnedAt }
            PlayerStatusSort.RAREST -> compareBy<RecentRow> { if (it.globalRarity < 0) Double.MAX_VALUE else it.globalRarity }
                .thenByDescending { it.earnedAt }
        },
    )

@HiltViewModel
class PlayerStatusViewModel @Inject constructor(
    private val achievements: AchievementController,
) : ViewModel() {
    private val _state = MutableStateFlow(PlayerStatusUiState())
    val uiState: StateFlow<PlayerStatusUiState> = _state.asStateFlow()
    private var collecting = false

    fun load() {
        // Each visit starts at the page's top item (Rarest when available), rather than restoring
        // the row that happened to be focused before the screen was closed.
        _state.update {
            it.copy(
                closed = false,
                options = null,
                message = null,
                focusedId = null,
                onRarest = true,
            )
        }
        if (collecting) return
        collecting = true
        viewModelScope.launch {
            combine(achievements.observeLibraryStanding(), achievements.observeRecentCoins()) { standing, recent -> standing to recent }
                .collect { (standing, recent) -> apply(standing, recent) }
        }
    }

    private fun apply(standing: LibraryStanding, recent: List<RecentCoin>) {
        val w = standing.wallet
        val p = w.levelProgress
        val counts = standing.walletCounts
        val providerByGame = standing.tracked.mapNotNull { it.libraryGameId?.let { id -> id to it.coins.provider } }.toMap()
        _state.update { old ->
            val allRows = recent.map { it.toRow(providerByGame) }
            val rows = visibleRows(allRows, old.sort, old.providerFilter)
            val rarest = standing.rarestEarned.firstOrNull()?.toCard()
            val initialLoad = old.recent.isEmpty() && old.rarest == null && old.focusedId == null
            val keepFocus = old.focusedId?.takeIf { id -> rows.any { it.id == id } }
            old.copy(
                rankLabel = w.rank.label,
                level = w.level,
                bones = w.bones,
                totalXp = w.totalCoins,
                xpIntoLevel = p.coinsIntoLevel,
                xpForNextLevel = p.coinsForNextLevel,
                nextIsBone = w.level == ShibaLevel.LEVELS_PER_BONE,
                bronze = counts.bronze,
                silver = counts.silver,
                gold = counts.gold,
                platinum = standing.gamesMastered,
                coinsEarned = standing.coinsEarned,
                coinsAvailable = standing.coinsAvailable,
                gamesTracked = standing.gamesTracked,
                gamesMastered = standing.gamesMastered,
                recent = rows,
                allRecent = allRows,
                rarest = rarest,
                focusedId = if (initialLoad) null else keepFocus ?: rows.firstOrNull()?.id,
                onRarest = if (rarest == null) false else if (initialLoad) true else old.onRarest,
            )
        }
    }

    private fun sortComparator(sort: PlayerStatusSort): Comparator<RecentRow> = when (sort) {
        PlayerStatusSort.NEWEST -> compareByDescending<RecentRow> { it.earnedAt }
        PlayerStatusSort.RAREST -> compareBy<RecentRow> { if (it.globalRarity < 0) Double.MAX_VALUE else it.globalRarity }.thenByDescending { it.earnedAt }
    }

    fun close() = _state.update { it.copy(closed = true) }
    fun onClosedHandled() = _state.update { it.copy(closed = false) }
    fun onOpenHandled() = _state.update { it.copy(openCoins = null) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun onRecentClick(id: String) {
        val alreadyFocused = _state.value.focusedId == id && !_state.value.onRarest
        _state.update { it.copy(focusedId = id, onRarest = false) }
        if (alreadyFocused) openFocused()
    }

    fun onRarestClick() {
        val alreadyFocused = _state.value.onRarest
        _state.update { it.copy(onRarest = true) }
        if (alreadyFocused) openFocused()
    }

    private fun openFocused() {
        val s = _state.value
        val target = if (s.onRarest) s.rarest?.coinsTarget else s.focusedRecent?.coinsTarget
        target?.let { _state.update { it.copy(openCoins = target) } }
    }

    fun openOptions() = _state.update { it.copy(options = PlayerStatusOptionsMenu(), message = null) }
    fun closeOptions() = _state.update { it.copy(options = null) }

    fun onOptionActivated(index: Int) {
        val row = _state.value.optionRows.getOrNull(index) ?: return
        when (val option = row.option) {
            is PlayerStatusOption.OpenGroup -> openOptionGroup(option.group)
            is PlayerStatusOption.Sort -> {
                _state.update { it.copy(sort = option.sort, options = null) }
                reloadRows()
            }
            is PlayerStatusOption.Provider -> {
                _state.update { it.copy(providerFilter = option.filter, options = null) }
                reloadRows()
            }
            PlayerStatusOption.SyncAll -> if (!_state.value.isSyncing) syncAll()
        }
    }

    private fun openOptionGroup(group: PlayerStatusOptionGroup) = _state.update { state ->
        val next = state.copy(options = PlayerStatusOptionsMenu(group = group))
        val selected = next.optionRows.indexOfFirst { it.checked }.coerceAtLeast(0)
        next.copy(options = PlayerStatusOptionsMenu(selectedIndex = selected, group = group))
    }

    private fun moveOptions(delta: Int) = _state.update { state ->
        val menu = state.options ?: return@update state
        val last = (state.optionRows.size - 1).coerceAtLeast(0)
        state.copy(options = menu.copy(selectedIndex = (menu.selectedIndex + delta).coerceIn(0, last)))
    }

    fun handleGamepadAction(action: GamepadAction) {
        if (_state.value.message != null) dismissMessage()
        if (_state.value.options != null) {
            when (action) {
                GamepadAction.NAVIGATE_UP -> moveOptions(-1)
                GamepadAction.NAVIGATE_DOWN -> moveOptions(1)
                GamepadAction.SELECT -> onOptionActivated(_state.value.options!!.selectedIndex)
                GamepadAction.BACK, GamepadAction.OPEN_CONTEXT_MENU -> closeOptions()
                else -> Unit
            }
            return
        }
        when (action) {
            GamepadAction.NAVIGATE_UP -> if (_state.value.onRarest) Unit else moveRecent(-1)
            GamepadAction.NAVIGATE_DOWN -> if (_state.value.onRarest) {
                _state.value.recent.firstOrNull()?.let { first ->
                    _state.update { it.copy(onRarest = false, focusedId = first.id) }
                }
            } else moveRecent(1)
            GamepadAction.NAVIGATE_RIGHT -> _state.update { if (it.rarest != null) it.copy(onRarest = true) else it }
            GamepadAction.NAVIGATE_LEFT -> _state.update { if (it.onRarest) it.copy(onRarest = false) else it }
            GamepadAction.SELECT -> openFocused()
            GamepadAction.OPEN_CONTEXT_MENU -> openOptions()
            GamepadAction.BACK -> close()
            else -> Unit
        }
    }

    private fun moveRecent(delta: Int) {
        val state = _state.value
        if (state.recent.isEmpty()) return
        val index = state.recent.indexOfFirst { it.id == state.focusedId }.coerceAtLeast(0)
        if (delta < 0 && index == 0) {
            if (state.rarest != null) _state.update { it.copy(onRarest = true) }
            return
        }
        _state.update {
            it.copy(focusedId = it.recent[(index + delta).coerceIn(0, it.recent.lastIndex)].id, onRarest = false)
        }
    }

    private fun reloadRows() = _state.update { state ->
        val rows = visibleRows(state.allRecent, state.sort, state.providerFilter)
        state.copy(recent = rows, focusedId = rows.firstOrNull()?.id, onRarest = false)
    }

    // The same selective update Settings and the hub run (present, matched games only). Progress
    // shows on the notice line; the result goes to the notification tray, like every other entry
    // point, so it is worded once.
    private fun syncAll() {
        viewModelScope.launch {
            _state.update { it.copy(isSyncing = true, options = null, message = null) }
            try {
                achievements.updateInstalledAchievements { done, total ->
                    _state.update {
                        it.copy(message = if (total > 0) "Checking $done of $total games…" else "Updating installed achievements…")
                    }
                }
            } finally {
                _state.update { it.copy(isSyncing = false, message = null) }
            }
        }
    }
}

private fun sortComparatorFor(sort: PlayerStatusSort): Comparator<RecentRow> = when (sort) {
    PlayerStatusSort.NEWEST -> compareByDescending { it.earnedAt }
    PlayerStatusSort.RAREST -> compareBy<RecentRow> { it.tier.ordinal }.thenBy { it.earnedAt }
}

private fun RecentCoin.toRow(providerByGame: Map<Long, AchievementProvider>) = RecentRow(
    id = "player-status:recent:${libraryGameId ?: "account"}:$coinTitle:$earnedAt",
    coinTitle = coinTitle,
    gameTitle = gameTitle,
    tier = tier,
    iconUrl = iconUrl,
    earnedAt = earnedAt,
    globalRarity = globalRarity,
    provider = libraryGameId?.let(providerByGame::get),
    coinsTarget = libraryGameId?.let { ShibaCoinsTarget.LibraryGame(it) },
)

private fun EarnedCoinRef.toCard() = RarestCard(
    coinTitle = coinTitle,
    gameTitle = gameTitle,
    tier = tier,
    globalRarity = globalRarity,
    iconUrl = iconUrl,
    coinsTarget = libraryGameId?.let { ShibaCoinsTarget.LibraryGame(it) },
)
