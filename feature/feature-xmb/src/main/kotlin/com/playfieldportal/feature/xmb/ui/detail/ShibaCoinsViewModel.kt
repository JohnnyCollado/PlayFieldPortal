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

/** The Auto-Match flow's current step (unlinked Steam-platform games only). */
enum class AutoMatchStep { CONFIRM_COPY, ENTER_APPID }

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
    // LOCAL_STEAM only: owned-vs-local classification from the link row; null = unknown (the
    // owned-games cache was never populated) and the UI stays silent about ownership.
    val ownership: com.playfieldportal.core.domain.achievement.LocalCopyOwnership? = null,
    // An account entry with no library game: syncable, but nothing to link or match.
    val accountOnly: Boolean = false,
    val summary: GameCoins? = null,
    val coins: List<CoinRow> = emptyList(),
    // Sorted + filtered view the screen renders; kept in sync with coins/sort/filter.
    val displayed: List<CoinRow> = emptyList(),
    val sort: CoinSort = CoinSort.TIER,
    val filter: CoinFilter = CoinFilter.ALL,
    // Controller focus, in on-screen top-to-bottom order: 0 = action (sync/link), 1 = sort,
    // 2 = filter, 3+ = coin rows. See the FOCUS_* constants below.
    val focusIndex: Int = 0,
    // Hidden coins the user chose to reveal (confirm/tap toggles). Session-only: cleared on open.
    val revealedIds: Set<String> = emptySet(),
    val isSyncing: Boolean = false,
    // Auto-Match flow (unlinked Steam-platform games): confirm whether the copy is a legit Steam
    // one, then fall through to manual appid entry when no automatic match is found.
    val autoMatchStep: AutoMatchStep? = null,
    // Controller selection on the confirm prompt: true = "Yes, legit Steam copy".
    val autoMatchYes: Boolean = true,
    // FOCUS_ACTION holds two controls for a linked Steam game (Change match | Sync now);
    // left/right picks which one confirm activates. False = Sync now, the primary action.
    val actionOnChangeMatch: Boolean = false,
    // The branch the user picked, so manual appid entry links the matching provider.
    val autoMatchLegit: Boolean = true,
    val isMatching: Boolean = false,
    val message: String? = null,
    val closed: Boolean = false,
)

// Focus order MUST match the on-screen top-to-bottom layout in ShibaCoinsScreen: the action row
// (Sync/Link) sits above the Sort and Filter rows, so it is focused first. These constants are the
// single source of that order — the screen references them by name (no magic numbers) so the visual
// and navigation order can never drift apart.
internal const val FOCUS_ACTION = 0
internal const val FOCUS_SORT = 1
internal const val FOCUS_FILTER = 2
internal const val FOCUS_COINS_START = 3

@HiltViewModel
class ShibaCoinsViewModel @Inject constructor(
    private val gameRepository: GameRepository,
    private val achievementRepository: AchievementController,
    private val autoMatcher: com.playfieldportal.feature.achievements.match.AchievementAutoMatcher,
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
        _state.update {
            it.copy(
                closed = false, focusIndex = 0, revealedIds = emptySet(),
                autoMatchStep = null, isMatching = false, actionOnChangeMatch = false,
            )
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
                        ownership = link?.ownership
                            ?.let(com.playfieldportal.core.domain.achievement.LocalCopyOwnership::fromName),
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
        val lastFocus = (FOCUS_COINS_START + s.displayed.size - 1).coerceAtLeast(0)
        when (action) {
            GamepadAction.NAVIGATE_UP -> _state.update { it.copy(focusIndex = (it.focusIndex - 1).coerceIn(0, lastFocus)) }
            GamepadAction.NAVIGATE_DOWN -> _state.update { it.copy(focusIndex = (it.focusIndex + 1).coerceIn(0, lastFocus)) }
            GamepadAction.NAVIGATE_LEFT -> when (s.focusIndex) {
                // Change match sits left of Sync now in the row.
                FOCUS_ACTION -> if (hasChangeMatch(s)) _state.update { it.copy(actionOnChangeMatch = true) }
                FOCUS_SORT -> cycleSort(-1)
                FOCUS_FILTER -> cycleFilter(-1)
                else -> Unit
            }
            GamepadAction.NAVIGATE_RIGHT -> when (s.focusIndex) {
                FOCUS_ACTION -> if (hasChangeMatch(s)) _state.update { it.copy(actionOnChangeMatch = false) }
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

    // Whether the action row currently offers Change match next to Sync now.
    private fun hasChangeMatch(s: ShibaCoinsUiState): Boolean =
        s.linked && !s.accountOnly && s.provider == AchievementProvider.STEAM

    private fun onActionSelect() {
        val s = _state.value
        when {
            hasChangeMatch(s) && s.actionOnChangeMatch -> changeLink()
            s.linked || s.accountOnly -> sync()
            s.provider == AchievementProvider.STEAM -> startAutoMatch()
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

    /** Opens the Auto-Match flow: first ask whether this is a legitimate Steam copy. */
    fun startAutoMatch() = _state.update { it.copy(autoMatchStep = AutoMatchStep.CONFIRM_COPY, autoMatchYes = true) }

    fun cancelAutoMatch() = _state.update { it.copy(autoMatchStep = null) }

    /**
     * Runs the branch the user picked: a legit copy resolves against Steam (embedded appid,
     * SteamGridDB, title), any other copy scans the windows game folders for Steam-emu data.
     * No match falls through to manual appid entry.
     */
    fun chooseAutoMatch(legit: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(autoMatchStep = null, autoMatchLegit = legit, isMatching = true) }
            val matched =
                if (legit) autoMatcher.matchSingleAsSteam(gameId)
                else autoMatcher.matchSingleAsLocalSteam(gameId)
            _state.update { it.copy(isMatching = false) }
            if (matched) sync()
            else _state.update { it.copy(autoMatchStep = AutoMatchStep.ENTER_APPID) }
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
            val provider =
                if (_state.value.autoMatchLegit) AchievementProvider.STEAM else AchievementProvider.LOCAL_STEAM
            achievementRepository.linkManually(gameId, provider, id)
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
        viewModelScope.launch {
            achievementRepository.unlink(gameId)
            // The row collapses to Auto-Match only — point confirm back at the primary action.
            _state.update { it.copy(actionOnChangeMatch = false) }
        }
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
    AchievementProvider.LOCAL_STEAM -> "Local Steam"
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
