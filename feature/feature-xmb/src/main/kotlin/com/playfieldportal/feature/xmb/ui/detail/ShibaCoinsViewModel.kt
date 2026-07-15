package com.playfieldportal.feature.xmb.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.database.entity.AccountAchievementEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.GameCoins
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CoinSort { TIER, EARNED, RAREST }
enum class CoinFilter { ALL, EARNED, LOCKED }

/** One coin as the dedicated screen renders it. */
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
)

data class ShibaCoinsUiState(
    val title: String = "",
    val platformLabel: String = "",
    val provider: AchievementProvider = AchievementProvider.RETRO_ACHIEVEMENTS,
    val linked: Boolean = false,
    // An account entry with no library game: syncable, but nothing to link or match.
    val accountOnly: Boolean = false,
    val summary: GameCoins? = null,
    val coins: List<CoinRow> = emptyList(),
    // Sorted + filtered view the screen renders; kept in sync with coins/sort/filter.
    val displayed: List<CoinRow> = emptyList(),
    val sort: CoinSort = CoinSort.TIER,
    val filter: CoinFilter = CoinFilter.ALL,
    // Controller focus: 0 = sort, 1 = filter, 2 = action (sync/link), 3+ = coin rows.
    val focusIndex: Int = 0,
    // Hidden coins the user chose to reveal (confirm/tap toggles). Session-only: cleared on open.
    val revealedIds: Set<String> = emptySet(),
    val isSyncing: Boolean = false,
    // Manual "Find on Steam" picker (Steam games only).
    val steamResults: List<com.playfieldportal.feature.achievements.provider.steam.SteamCandidate> = emptyList(),
    val isSearchingSteam: Boolean = false,
    val message: String? = null,
    val closed: Boolean = false,
)

private const val FOCUS_SORT = 0
private const val FOCUS_FILTER = 1
private const val FOCUS_ACTION = 2
private const val FOCUS_COINS_START = 3

@HiltViewModel
class ShibaCoinsViewModel @Inject constructor(
    private val gameRepository: GameRepository,
    private val achievementRepository: AchievementController,
) : ViewModel() {

    private val _state = MutableStateFlow(ShibaCoinsUiState())
    val uiState: StateFlow<ShibaCoinsUiState> = _state.asStateFlow()

    private var gameId: Long = -1
    private var target: ShibaCoinsTarget = ShibaCoinsTarget.LibraryGame(-1)
    private var loadJobs = mutableListOf<kotlinx.coroutines.Job>()

    fun load(target: ShibaCoinsTarget) {
        this.target = target
        // This ViewModel is retained across open/close, so clear the stale closed flag (otherwise
        // the screen's close-effect fires immediately on reopen), reset focus to the top, and
        // re-hide any revealed hidden coins (reveals are session-only).
        _state.update { it.copy(closed = false, focusIndex = 0, revealedIds = emptySet()) }
        loadJobs.forEach { it.cancel() }
        loadJobs.clear()
        when (target) {
            is ShibaCoinsTarget.LibraryGame -> loadLibraryGame(target.gameId)
            is ShibaCoinsTarget.AccountEntry -> loadAccountEntry(target)
        }
    }

    private fun loadLibraryGame(id: Long) {
        gameId = id
        _state.update { it.copy(accountOnly = false) }
        loadJobs += viewModelScope.launch {
            val game = gameRepository.getById(id)
            _state.update {
                it.copy(
                    title = game?.displayTitle ?: "",
                    platformLabel = game?.platformId?.uppercase() ?: "",
                    provider = providerForPlatform(game?.platformId),
                )
            }
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
                        coins = coins.map { e -> e.toRow() },
                        linked = link != null,
                        provider = link?.let { l -> AchievementProvider.fromName(l.provider) } ?: it.provider,
                    ).withDisplayed()
                }
            }
        }
    }

    private fun loadAccountEntry(entry: ShibaCoinsTarget.AccountEntry) {
        gameId = -1
        _state.update {
            it.copy(accountOnly = true, linked = false, provider = entry.provider, platformLabel = providerLabel(entry.provider))
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
                        coins = coins.map { e -> e.toRow() },
                    ).withDisplayed()
                }
            }
        }
    }

    fun setSort(sort: CoinSort) = _state.update { it.copy(sort = sort).withDisplayed() }
    fun setFilter(filter: CoinFilter) = _state.update { it.copy(filter = filter).withDisplayed() }

    /** Toggles a hidden coin between redacted and revealed. No-op for coins that aren't hidden. */
    fun toggleReveal(coin: CoinRow) {
        if (!coin.isHidden || coin.isEarned) return
        _state.update {
            it.copy(revealedIds = if (coin.id in it.revealedIds) it.revealedIds - coin.id else it.revealedIds + coin.id)
        }
    }
    fun dismissMessage() = _state.update { it.copy(message = null) }
    fun close() = _state.update { it.copy(closed = true) }

    /** Clears the closed flag once the screen has acted on it, so the next open isn't cut short. */
    fun onClosedHandled() = _state.update { it.copy(closed = false) }

    /** Controller input forwarded from the shell while this overlay is open. */
    fun handleGamepadAction(action: GamepadAction) {
        val s = _state.value
        val lastFocus = (FOCUS_COINS_START + s.displayed.size - 1).coerceAtLeast(0)
        when (action) {
            GamepadAction.NAVIGATE_UP -> _state.update { it.copy(focusIndex = (it.focusIndex - 1).coerceIn(0, lastFocus)) }
            GamepadAction.NAVIGATE_DOWN -> _state.update { it.copy(focusIndex = (it.focusIndex + 1).coerceIn(0, lastFocus)) }
            GamepadAction.NAVIGATE_LEFT -> when (s.focusIndex) {
                FOCUS_SORT -> cycleSort(-1)
                FOCUS_FILTER -> cycleFilter(-1)
                else -> Unit
            }
            GamepadAction.NAVIGATE_RIGHT -> when (s.focusIndex) {
                FOCUS_SORT -> cycleSort(1)
                FOCUS_FILTER -> cycleFilter(1)
                else -> Unit
            }
            GamepadAction.SELECT -> when (s.focusIndex) {
                FOCUS_SORT -> cycleSort(1)
                FOCUS_FILTER -> cycleFilter(1)
                FOCUS_ACTION -> onActionSelect()
                // Confirm on a hidden coin row reveals its details; confirm again re-hides.
                else -> s.displayed.getOrNull(s.focusIndex - FOCUS_COINS_START)?.let { toggleReveal(it) }
            }
            GamepadAction.BACK -> close()
            else -> Unit
        }
    }

    private fun onActionSelect() {
        val s = _state.value
        when {
            s.linked || s.accountOnly -> sync()
            s.provider == AchievementProvider.STEAM -> resolveByTitle()
            // RetroAchievements is hash-only — nothing for the user to enter.
            else -> _state.update { it.copy(message = "RetroAchievements games link automatically by ROM hash") }
        }
    }

    private fun cycleSort(dir: Int) {
        val entries = CoinSort.entries
        setSort(entries[(_state.value.sort.ordinal + dir + entries.size) % entries.size])
    }

    private fun cycleFilter(dir: Int) {
        val entries = CoinFilter.entries
        setFilter(entries[(_state.value.filter.ordinal + dir + entries.size) % entries.size])
    }

    private fun ShibaCoinsUiState.withDisplayed(): ShibaCoinsUiState {
        val d = coins.arrange(sort, filter)
        return copy(displayed = d, focusIndex = focusIndex.coerceIn(0, (FOCUS_COINS_START + d.size - 1).coerceAtLeast(0)))
    }

    /** Links the game to the pasted provider id (RA game id / Steam appid) and syncs. */
    fun link(providerGameId: String) {
        val id = providerGameId.trim()
        if (id.isBlank()) return
        viewModelScope.launch {
            achievementRepository.linkManually(gameId, _state.value.provider, id)
            sync()
        }
    }

    /** Steam only: search the Steam app list for candidates matching [query] (the manual picker). */
    fun searchSteam(query: String) {
        if (query.isBlank()) {
            _state.update { it.copy(steamResults = emptyList()) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSearchingSteam = true) }
            val results = achievementRepository.searchSteam(query)
            _state.update { it.copy(isSearchingSteam = false, steamResults = results) }
        }
    }

    /** Links a Steam appid the user picked from the search results, then syncs. */
    fun linkSteamAppId(appId: String) {
        _state.update { it.copy(steamResults = emptyList()) }
        link(appId)
    }

    /** Steam only: match this game to an appid by its title variants, link it, and sync. */
    fun resolveByTitle() {
        viewModelScope.launch {
            val appId = achievementRepository.resolveSteamByGame(gameId)
            if (appId != null) sync()
            else _state.update { it.copy(message = "No Steam match for \"${it.title}\"") }
        }
    }

    /** Removes the current link so the user can re-enter it (edit a wrong match). */
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
        ProviderSyncResult.NotLinked -> "Link this game to a provider id first"
        ProviderSyncResult.MissingCredentials -> "Add your key in Settings ▸ Shiba Coins"
        ProviderSyncResult.ProfileNotPublic -> "Your Steam profile's Game Details are private"
        ProviderSyncResult.NotFound -> "No achievements found for this game"
        is ProviderSyncResult.Failed -> "Sync failed: ${result.reason}"
    }

    private fun providerForPlatform(platformId: String?): AchievementProvider =
        if (platformId == "windows") AchievementProvider.STEAM else AchievementProvider.RETRO_ACHIEVEMENTS
}

/** The provider's display name, shown where a library game would show its platform. */
internal fun providerLabel(provider: AchievementProvider): String = when (provider) {
    AchievementProvider.RETRO_ACHIEVEMENTS -> "RetroAchievements"
    AchievementProvider.STEAM -> "Steam"
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

/** Pure sort + filter for the coin list — used by the screen and unit-tested directly. */
fun List<CoinRow>.arrange(sort: CoinSort, filter: CoinFilter): List<CoinRow> {
    val filtered = when (filter) {
        CoinFilter.ALL -> this
        CoinFilter.EARNED -> filter { it.isEarned }
        CoinFilter.LOCKED -> filter { !it.isEarned }
    }
    return when (sort) {
        // Lowest tier first (Bronze at the top, up to Gold), then rarest within the tier.
        CoinSort.TIER -> filtered.sortedWith(compareBy({ tierRank(it.tier) }, { it.rarityRank }))
        CoinSort.EARNED -> filtered.sortedWith(compareByDescending<CoinRow> { it.isEarned }.thenByDescending { it.earnedAt ?: 0L })
        CoinSort.RAREST -> filtered.sortedBy { it.rarityRank }
    }
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
