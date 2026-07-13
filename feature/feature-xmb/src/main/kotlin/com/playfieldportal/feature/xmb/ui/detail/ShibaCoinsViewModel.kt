package com.playfieldportal.feature.xmb.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.database.entity.AchievementEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.GameCoins
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.AchievementRepository
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
    val summary: GameCoins? = null,
    val coins: List<CoinRow> = emptyList(),
    val sort: CoinSort = CoinSort.TIER,
    val filter: CoinFilter = CoinFilter.ALL,
    val isSyncing: Boolean = false,
    val message: String? = null,
    val closed: Boolean = false,
)

@HiltViewModel
class ShibaCoinsViewModel @Inject constructor(
    private val gameRepository: GameRepository,
    private val achievementRepository: AchievementRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ShibaCoinsUiState())
    val uiState: StateFlow<ShibaCoinsUiState> = _state.asStateFlow()

    private var gameId: Long = -1

    fun load(id: Long) {
        gameId = id
        viewModelScope.launch {
            val game = gameRepository.getById(id)
            _state.update {
                it.copy(
                    title = game?.displayTitle ?: "",
                    platformLabel = game?.platformId?.uppercase() ?: "",
                    provider = providerForPlatform(game?.platformId),
                )
            }
        }
        viewModelScope.launch {
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
                    )
                }
            }
        }
    }

    fun setSort(sort: CoinSort) = _state.update { it.copy(sort = sort) }
    fun setFilter(filter: CoinFilter) = _state.update { it.copy(filter = filter) }
    fun dismissMessage() = _state.update { it.copy(message = null) }
    fun close() = _state.update { it.copy(closed = true) }

    /** Links the game to the pasted provider id (RA game id / Steam appid) and syncs. */
    fun link(providerGameId: String) {
        val id = providerGameId.trim()
        if (id.isBlank()) return
        viewModelScope.launch {
            achievementRepository.linkManually(gameId, _state.value.provider, id)
            sync()
        }
    }

    /** Steam only: match this game's title to an appid, link it, and sync. */
    fun resolveByTitle() {
        viewModelScope.launch {
            val appId = achievementRepository.resolveSteamLink(gameId, _state.value.title)
            if (appId != null) sync()
            else _state.update { it.copy(message = "No Steam match for \"${it.title}\"") }
        }
    }

    fun sync() {
        viewModelScope.launch {
            _state.update { it.copy(isSyncing = true) }
            val result = achievementRepository.syncGameById(gameId)
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

private fun AchievementEntity.toRow() = CoinRow(
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
        // Rarest tier first, then rarest within the tier.
        CoinSort.TIER -> filtered.sortedWith(compareBy({ tierRank(it.tier) }, { it.globalRarity }))
        CoinSort.EARNED -> filtered.sortedWith(compareByDescending<CoinRow> { it.isEarned }.thenByDescending { it.earnedAt ?: 0L })
        CoinSort.RAREST -> filtered.sortedBy { it.globalRarity }
    }
}

private fun tierRank(tier: ShibaTier): Int = when (tier) {
    ShibaTier.GOLD -> 0
    ShibaTier.SILVER -> 1
    ShibaTier.BRONZE -> 2
    ShibaTier.PLATINUM -> 3
}
