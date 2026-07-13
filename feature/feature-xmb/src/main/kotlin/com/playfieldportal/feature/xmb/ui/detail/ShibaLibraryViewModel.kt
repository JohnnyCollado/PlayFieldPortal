package com.playfieldportal.feature.xmb.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.domain.achievement.GameStanding
import com.playfieldportal.core.domain.achievement.UntrackedGame
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.AchievementRepository
import com.playfieldportal.feature.xmb.viewmodel.ShibaLibraryMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One game row in the fullscreen library (tracked shows coins/progress; untracked shows a reason). */
data class ShibaLibraryRow(
    val gameId: Long,
    val title: String,
    val platformLabel: String,
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
    // Wallet footer.
    val totalCoinScore: Int = 0,
    val level: Int = 1,
    val rank: String = "",
    val coinsIntoLevel: Int = 0,
    val coinsForNextLevel: Int = 0,
    val closed: Boolean = false,
) {
    val focused: ShibaLibraryRow? get() = rows.getOrNull(focusIndex)
    val nextRewardCoins: Int get() = (coinsForNextLevel - coinsIntoLevel).coerceAtLeast(0)
    val levelFraction: Float
        get() = if (coinsForNextLevel <= 0) 0f else (coinsIntoLevel.toFloat() / coinsForNextLevel).coerceIn(0f, 1f)
}

@HiltViewModel
class ShibaLibraryViewModel @Inject constructor(
    private val gameRepository: GameRepository,
    private val achievementRepository: AchievementRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ShibaLibraryUiState())
    val uiState: StateFlow<ShibaLibraryUiState> = _state.asStateFlow()

    private var latest: Pair<com.playfieldportal.core.domain.achievement.LibraryStanding, Map<Long, Game>>? = null
    private var collecting = false

    fun load(mode: ShibaLibraryMode) {
        _state.update { it.copy(mode = mode, closed = false, focusIndex = 0) }
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
            GamepadAction.BACK -> close()
            else -> Unit
        }
    }

    fun setFocus(index: Int) = _state.update { it.copy(focusIndex = index.coerceIn(0, (it.rows.size - 1).coerceAtLeast(0))) }

    private fun rebuild() {
        val (standing, byId) = latest ?: return
        val rows = when (_state.value.mode) {
            ShibaLibraryMode.TRACKED ->
                standing.tracked.sortedBy { it.title.lowercase() }.map { it.toRow(byId[it.gameId]) }
            ShibaLibraryMode.UNTRACKED ->
                standing.untracked.sortedBy { it.title.lowercase() }.map { it.toRow(byId[it.gameId]) }
        }
        val w = standing.wallet
        val p = w.levelProgress
        _state.update {
            it.copy(
                rows = rows,
                focusIndex = it.focusIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0)),
                totalCoinScore = w.totalCoins,
                level = w.level,
                rank = w.rank.label,
                coinsIntoLevel = p.coinsIntoLevel,
                coinsForNextLevel = p.coinsForNextLevel,
            )
        }
    }

    private fun GameStanding.toRow(game: Game?) = ShibaLibraryRow(
        gameId = gameId,
        title = game?.displayTitle ?: title,
        platformLabel = game?.platformId?.let(::platformDisplay) ?: "",
        artworkUri = game?.artworkUri,
        logoUri = game?.logoUri,
        progress = coins.progress,
        bronzeEarned = coins.earned.bronze, bronzeTotal = coins.total.bronze,
        silverEarned = coins.earned.silver, silverTotal = coins.total.silver,
        goldEarned = coins.earned.gold, goldTotal = coins.total.gold,
        mastered = coins.isMastered,
        reason = null,
    )

    private fun UntrackedGame.toRow(game: Game?) = ShibaLibraryRow(
        gameId = gameId,
        title = game?.displayTitle ?: title,
        platformLabel = platformDisplay(platformId),
        artworkUri = game?.artworkUri,
        logoUri = game?.logoUri,
        progress = 0f,
        bronzeEarned = 0, bronzeTotal = 0,
        silverEarned = 0, silverTotal = 0,
        goldEarned = 0, goldTotal = 0,
        mastered = false,
        reason = reason,
    )
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
