package com.playfieldportal.feature.achievements

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.database.dao.AchievementDao
import com.playfieldportal.core.data.database.dao.AchievementSetDao
import com.playfieldportal.core.data.database.entity.AchievementEntity
import com.playfieldportal.core.data.database.entity.AchievementSetEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.CoinCounts
import com.playfieldportal.core.domain.achievement.CoinWallet
import com.playfieldportal.core.domain.achievement.GameCoins
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.RetroAchievementsApi
import com.playfieldportal.feature.achievements.api.SteamAchievementsApi
import com.playfieldportal.feature.achievements.api.SyncedCoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point for the coin system: offline-first reads straight from Room, and a
 * network sync that maps a provider fetch into the persisted set + per-coin rows. The wallet is
 * derived reactively from the set summaries, so it updates itself whenever a sync lands — nothing
 * to recompute by hand. See docs/shiba-coins-achievements-plan.md.
 */
@Singleton
class AchievementRepository @Inject constructor(
    private val steamApi: SteamAchievementsApi,
    private val retroApi: RetroAchievementsApi,
    private val credentials: AchievementCredentialsProvider,
    private val setDao: AchievementSetDao,
    private val coinDao: AchievementDao,
) {
    /** This game's coin summary (progress, tally, mastery), or null if never synced. */
    fun observeGameCoins(gameId: Long): Flow<GameCoins?> =
        setDao.observeForGame(gameId).map { it?.toGameCoins() }

    /** The raw per-coin rows for a game's dedicated coins screen. */
    fun observeCoins(gameId: Long): Flow<List<AchievementEntity>> =
        coinDao.observeForGame(gameId)

    /** The account-wide Shiba wallet (total coins -> level + rank), derived from every set. */
    fun observeWallet(): Flow<CoinWallet> =
        setDao.observeWalletCoins().map { CoinWallet(it) }

    /**
     * Fetches [providerGameId] from [provider] and persists the result. On success the per-coin
     * rows are replaced (pruning coins the provider dropped) and the summary is rewritten; any
     * other outcome leaves the stored data untouched and is returned for the UI to surface.
     */
    suspend fun syncGame(
        gameId: Long,
        provider: AchievementProvider,
        providerGameId: String,
    ): ProviderSyncResult {
        val result = when (provider) {
            AchievementProvider.STEAM -> steamApi.fetch(providerGameId)
            AchievementProvider.RETRO_ACHIEVEMENTS -> retroApi.fetch(providerGameId)
        }
        if (result !is ProviderSyncResult.Success) return result

        val now = System.currentTimeMillis()
        coinDao.deleteForGame(gameId)
        coinDao.upsertAll(result.coins.map { it.toEntity(gameId, provider) })
        setDao.upsert(summaryOf(gameId, provider, result.providerGameId, result.coins, now))
        credentials.setLastSyncedAt(now)
        return result
    }
}

// ── Mappers ───────────────────────────────────────────────────────────────────

private fun AchievementSetEntity.toGameCoins(): GameCoins? {
    val p = AchievementProvider.fromName(provider) ?: return null
    return GameCoins(
        provider = p,
        earned = CoinCounts(bronzeEarned, silverEarned, goldEarned),
        total = CoinCounts(bronzeTotal, silverTotal, goldTotal),
    )
}

private fun SyncedCoin.toEntity(gameId: Long, provider: AchievementProvider) = AchievementEntity(
    gameId = gameId,
    provider = provider.name,
    providerAchievementId = providerAchievementId,
    title = title,
    description = description,
    tier = tier.name,
    globalRarity = globalRarity,
    iconUrl = iconUrl,
    isHidden = isHidden,
    isEarned = isEarned,
    earnedAt = earnedAt,
)

private fun summaryOf(
    gameId: Long,
    provider: AchievementProvider,
    providerGameId: String,
    coins: List<SyncedCoin>,
    now: Long,
): AchievementSetEntity {
    fun count(tier: ShibaTier, earnedOnly: Boolean) =
        coins.count { it.tier == tier && (!earnedOnly || it.isEarned) }
    return AchievementSetEntity(
        gameId = gameId,
        provider = provider.name,
        providerGameId = providerGameId,
        bronzeTotal = count(ShibaTier.BRONZE, earnedOnly = false),
        silverTotal = count(ShibaTier.SILVER, earnedOnly = false),
        goldTotal = count(ShibaTier.GOLD, earnedOnly = false),
        bronzeEarned = count(ShibaTier.BRONZE, earnedOnly = true),
        silverEarned = count(ShibaTier.SILVER, earnedOnly = true),
        goldEarned = count(ShibaTier.GOLD, earnedOnly = true),
        // Platinum crown = every individual coin earned.
        mastered = coins.isNotEmpty() && coins.all { it.isEarned },
        lastSyncedAt = now,
    )
}
