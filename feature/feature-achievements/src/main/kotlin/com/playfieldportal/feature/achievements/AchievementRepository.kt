package com.playfieldportal.feature.achievements

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.database.dao.AchievementDao
import com.playfieldportal.core.data.database.dao.AchievementSetDao
import com.playfieldportal.core.data.database.dao.ProviderGameLinkDao
import com.playfieldportal.core.data.database.dao.EarnedCoinRow
import com.playfieldportal.core.data.database.dao.GameSetRow
import com.playfieldportal.core.data.database.entity.AchievementEntity
import com.playfieldportal.core.data.database.entity.AchievementSetEntity
import com.playfieldportal.core.data.database.entity.ProviderGameLinkEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.feature.achievements.api.SteamAppListResolver
import com.playfieldportal.core.domain.achievement.CoinCounts
import com.playfieldportal.core.domain.achievement.CoinWallet
import com.playfieldportal.core.domain.achievement.EarnedCoinRef
import com.playfieldportal.core.domain.achievement.GameCoins
import com.playfieldportal.core.domain.achievement.GameStanding
import com.playfieldportal.core.domain.achievement.LibraryStanding
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.RetroAchievementsApi
import com.playfieldportal.feature.achievements.api.SteamAchievementsApi
import com.playfieldportal.feature.achievements.api.SyncedCoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
    private val linkDao: ProviderGameLinkDao,
    private val steamResolver: SteamAppListResolver,
) {
    /** This game's provider link (which provider + id it syncs from), or null if unlinked. */
    fun observeLink(gameId: Long): Flow<ProviderGameLinkEntity?> = linkDao.observeForGame(gameId)
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
     * The whole-library standing for the Shiba Coins hub: the wallet, every tracked game's standing,
     * and the [rarestLimit] rarest earned coins. Counts and the mastery lens derive from the tracked
     * list. Reads only cached rows — no network — so the hub is offline-first.
     */
    fun observeLibraryStanding(rarestLimit: Int = 15): Flow<LibraryStanding> =
        combine(
            observeWallet(),
            setDao.observeGameSets(),
            coinDao.observeRarestEarned(rarestLimit),
        ) { wallet, sets, rarest ->
            LibraryStanding(
                wallet = wallet,
                tracked = sets.mapNotNull { it.toGameStanding() },
                rarestEarned = rarest.mapNotNull { it.toEarnedCoinRef() },
            )
        }

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

    /** Links a game to a provider id by hand — the always-works path (and the only one for RA yet). */
    suspend fun linkManually(gameId: Long, provider: AchievementProvider, providerGameId: String) {
        linkDao.upsert(
            ProviderGameLinkEntity(
                gameId = gameId,
                provider = provider.name,
                providerGameId = providerGameId.trim(),
                source = "MANUAL",
                resolvedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Tries to auto-link a game to Steam by matching its [title] against the Steam app list. Stores
     * and returns the resolved appid, or null when there is no match.
     */
    suspend fun resolveSteamLink(gameId: Long, title: String): String? {
        val appId = steamResolver.resolveAppId(title) ?: return null
        linkDao.upsert(
            ProviderGameLinkEntity(
                gameId = gameId,
                provider = AchievementProvider.STEAM.name,
                providerGameId = appId,
                source = "STEAM_TITLE",
                resolvedAt = System.currentTimeMillis(),
            ),
        )
        return appId
    }

    /** Removes a game's provider link so it can be re-matched (edit a wrong auto-match). */
    suspend fun unlink(gameId: Long) = linkDao.deleteForGame(gameId)

    /** Syncs a game from its stored link; [ProviderSyncResult.NotLinked] if it has none yet. */
    suspend fun syncGameById(gameId: Long): ProviderSyncResult {
        val link = linkDao.getForGame(gameId) ?: return ProviderSyncResult.NotLinked
        val provider = AchievementProvider.fromName(link.provider) ?: return ProviderSyncResult.NotLinked
        return syncGame(gameId, provider, link.providerGameId)
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

private fun GameSetRow.toGameStanding(): GameStanding? {
    val p = AchievementProvider.fromName(provider) ?: return null
    return GameStanding(
        gameId = gameId,
        title = title,
        coins = GameCoins(
            provider = p,
            earned = CoinCounts(bronzeEarned, silverEarned, goldEarned),
            total = CoinCounts(bronzeTotal, silverTotal, goldTotal),
        ),
    )
}

private fun EarnedCoinRow.toEarnedCoinRef(): EarnedCoinRef? {
    val t = runCatching { ShibaTier.valueOf(tier) }.getOrNull() ?: return null
    return EarnedCoinRef(
        gameId = gameId,
        gameTitle = gameTitle,
        coinTitle = title,
        tier = t,
        globalRarity = globalRarity,
        iconUrl = iconUrl,
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
