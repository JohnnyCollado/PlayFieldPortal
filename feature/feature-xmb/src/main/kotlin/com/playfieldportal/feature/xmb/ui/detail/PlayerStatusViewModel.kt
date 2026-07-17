package com.playfieldportal.feature.xmb.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.domain.achievement.EarnedCoinRef
import com.playfieldportal.core.domain.achievement.LibraryStanding
import com.playfieldportal.core.domain.achievement.RecentCoin
import com.playfieldportal.core.domain.achievement.ShibaLevel
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.core.domain.model.GamepadAction
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
    val coinTitle: String,
    val gameTitle: String,
    val tier: ShibaTier,
    val iconUrl: String?,
    val earnedAt: Long,
    // Set only when the coin's game is in the library — activating the row opens its coins overlay.
    val coinsTarget: ShibaCoinsTarget?,
)

/** The single rarest earned achievement, as the "Rarest Unlocked" card renders it. */
data class RarestCard(
    val coinTitle: String,
    val gameTitle: String,
    val tier: ShibaTier,
    val globalRarity: Double,
    val iconUrl: String?,
    val coinsTarget: ShibaCoinsTarget?,
)

data class PlayerStatusUiState(
    val rankLabel: String = "",
    val level: Int = 1,
    val bones: Int = 0,
    // XP is the weighted score economy (the level curve). "Coins" are the achievement counts below.
    val totalXp: Int = 0,
    val xpIntoLevel: Int = 0,
    val xpForNextLevel: Int = 0,
    // At the top of a Bone cycle the next level mints a Bone rather than reading "level 101".
    val nextIsBone: Boolean = false,
    // Account-wide earned achievement ("coin") counts by tier; platinum = mastered games.
    val bronze: Int = 0,
    val silver: Int = 0,
    val gold: Int = 0,
    val platinum: Int = 0,
    val coinsEarned: Int = 0,
    val coinsAvailable: Int = 0,
    val gamesTracked: Int = 0,
    val gamesMastered: Int = 0,
    val recent: List<RecentRow> = emptyList(),
    val rarest: RarestCard? = null,
    // Controller focus is two regions: the Recent list (UP/DOWN scrolls through its rows) and the
    // Rarest card. RIGHT jumps from Recent to Rarest; LEFT returns to Recent. While the rarest
    // card holds focus, UP/DOWN scroll the PAGE instead: [rarestPageNudge] accumulates +1 per
    // DOWN and -1 per UP, and the screen turns deltas into relative scrolls.
    val recentIndex: Int = 0,
    val onRarest: Boolean = false,
    val rarestPageNudge: Int = 0,
    val closed: Boolean = false,
    // A row the user activated — the screen opens its coins overlay and calls onOpenHandled.
    val openCoins: ShibaCoinsTarget? = null,
) {
    val xpToNext: Int get() = (xpForNextLevel - xpIntoLevel).coerceAtLeast(0)
    val levelFraction: Float
        get() = if (xpForNextLevel <= 0) 0f else (xpIntoLevel.toFloat() / xpForNextLevel).coerceIn(0f, 1f)

    /** True when recent row [index] currently holds controller focus. */
    fun recentFocused(index: Int): Boolean = !onRarest && recentIndex == index

    /** True when the rarest card currently holds controller focus. */
    val rarestFocused: Boolean get() = onRarest
}

@HiltViewModel
class PlayerStatusViewModel @Inject constructor(
    private val achievements: AchievementController,
) : ViewModel() {

    private val _state = MutableStateFlow(PlayerStatusUiState())
    val uiState: StateFlow<PlayerStatusUiState> = _state.asStateFlow()

    private var collecting = false

    fun load() {
        // Retained across open/close: clear the stale closed flag and reset focus to the top row.
        _state.update { it.copy(closed = false, recentIndex = 0, onRarest = false, rarestPageNudge = 0) }
        if (collecting) return
        collecting = true
        viewModelScope.launch {
            combine(
                achievements.observeLibraryStanding(),
                achievements.observeRecentCoins(),
            ) { standing, recent -> standing to recent }
                .collect { (standing, recent) -> apply(standing, recent) }
        }
    }

    private fun apply(standing: LibraryStanding, recent: List<RecentCoin>) {
        val w = standing.wallet
        val p = w.levelProgress
        val counts = standing.walletCounts
        _state.update {
            it.copy(
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
                recent = recent.map { c -> c.toRow() },
                rarest = standing.rarestEarned.firstOrNull()?.toCard(),
            ).clampFocus()
        }
    }

    private fun PlayerStatusUiState.clampFocus(): PlayerStatusUiState {
        val lastRecent = (recent.size - 1).coerceAtLeast(0)
        // Keep focus valid across data refreshes: drop rarest focus if it vanished; if there are no
        // recent rows but a rarest card exists, park focus on it so navigation always has a target.
        val rarestFocus = when {
            rarest == null -> false
            recent.isEmpty() -> true
            else -> onRarest
        }
        return copy(recentIndex = recentIndex.coerceIn(0, lastRecent), onRarest = rarestFocus)
    }

    fun close() = _state.update { it.copy(closed = true) }
    fun onClosedHandled() = _state.update { it.copy(closed = false) }
    fun onOpenHandled() = _state.update { it.copy(openCoins = null) }

    /** Touch: focus and open a recent row. */
    fun onRecentClick(index: Int) {
        _state.update { it.copy(recentIndex = index.coerceIn(0, (it.recent.size - 1).coerceAtLeast(0)), onRarest = false) }
        openFocused()
    }

    /** Touch: focus and open the rarest card. */
    fun onRarestClick() {
        _state.update { it.copy(onRarest = true) }
        openFocused()
    }

    private fun openFocused() {
        val s = _state.value
        val target = if (s.onRarest) s.rarest?.coinsTarget else s.recent.getOrNull(s.recentIndex)?.coinsTarget
        target?.let { t -> _state.update { it.copy(openCoins = t) } }
    }

    /** Controller input forwarded from the shell while this overlay is open. */
    fun handleGamepadAction(action: GamepadAction) {
        val s = _state.value
        val lastRecent = (s.recent.size - 1).coerceAtLeast(0)
        when (action) {
            // UP/DOWN scroll the recent list; while the rarest card holds focus they scroll the
            // PAGE instead (relative nudges the screen converts to scrolls).
            GamepadAction.NAVIGATE_UP -> _state.update {
                if (it.onRarest) it.copy(rarestPageNudge = it.rarestPageNudge - 1)
                else it.copy(recentIndex = (it.recentIndex - 1).coerceIn(0, lastRecent))
            }
            GamepadAction.NAVIGATE_DOWN -> _state.update {
                if (it.onRarest) it.copy(rarestPageNudge = it.rarestPageNudge + 1)
                else it.copy(recentIndex = (it.recentIndex + 1).coerceIn(0, lastRecent))
            }
            // RIGHT jumps to the rarest card (it sits in the right column); LEFT returns to the
            // recent list on the left — matching the on-screen layout. With no recent rows the
            // rarest card is the only focus target, so LEFT stays put rather than stranding focus.
            GamepadAction.NAVIGATE_RIGHT -> if (!s.onRarest && s.rarest != null) _state.update { it.copy(onRarest = true) }
            GamepadAction.NAVIGATE_LEFT -> if (s.onRarest && s.recent.isNotEmpty()) _state.update { it.copy(onRarest = false) }
            GamepadAction.SELECT -> openFocused()
            GamepadAction.BACK -> close()
            else -> Unit
        }
    }
}

private fun RecentCoin.toRow() = RecentRow(
    coinTitle = coinTitle,
    gameTitle = gameTitle,
    tier = tier,
    iconUrl = iconUrl,
    earnedAt = earnedAt,
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
