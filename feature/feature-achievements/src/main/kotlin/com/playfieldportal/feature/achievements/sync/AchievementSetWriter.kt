package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.database.dao.AccountAchievementDao
import com.playfieldportal.core.data.database.dao.AccountAchievementSetDao
import com.playfieldportal.core.data.database.entity.AccountAchievementEntity
import com.playfieldportal.core.data.database.entity.AccountAchievementSetEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.feature.achievements.api.SyncedCoin
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists one successful provider fetch: the per-coin rows and the set summary are replaced in a
 * single transaction (pruning coins the provider dropped), so an interrupted write never exposes
 * an empty set. Returns whether the user's earned progress actually changed, so an unchanged check
 * is never reported as new achievements. Callers only reach this with a Success — every other
 * outcome leaves stored data untouched.
 */
@Singleton
class AchievementSetWriter @Inject constructor(
    private val setDao: AccountAchievementSetDao,
    private val coinDao: AccountAchievementDao,
    private val credentials: AchievementCredentialsProvider,
    private val clock: AchievementClock,
) {
    suspend fun write(identity: AchievementIdentity, title: String, coins: List<SyncedCoin>): Boolean {
        val now = clock.now()
        val provider = identity.provider
        val id = identity.providerGameId
        val storedSet = setDao.getSet(provider.name, id)
        val storedCoins = coinDao.getForSet(provider.name, id)

        val fresh = carryDescriptions(coins, storedCoins)
        val tiered = stabilizeTiers(provider, storedSet, storedCoins, fresh, now)
        val changed = storedSet == null || earnedStateOf(storedCoins) != earnedStateOf(tiered)

        setDao.replaceSet(
            summaryOf(provider, id, tiered, now).copy(
                title = title.ifBlank { storedSet?.title.orEmpty() },
                iconUrl = storedSet?.iconUrl,
            ),
            tiered.map { it.toEntity(provider, id) },
        )
        credentials.setLastSyncedAt(now)
        return changed
    }

    // A hidden coin's description, once learned (community-page enrichment), is kept when a later
    // fetch withholds it again — so a routine refresh never needs to re-scrape it.
    private fun carryDescriptions(fresh: List<SyncedCoin>, stored: List<AccountAchievementEntity>): List<SyncedCoin> {
        if (stored.isEmpty()) return fresh
        val known = stored.filter { it.description.isNotBlank() }.associate { it.providerAchievementId to it.description }
        return fresh.map { coin ->
            if (coin.description.isNotBlank()) coin
            else known[coin.providerAchievementId]?.let { coin.copy(description = it) } ?: coin
        }
    }

    /**
     * Steam tiers (STEAM and LOCAL_STEAM alike) derive from global unlock percentages, which
     * drift as more players unlock — without a damper a coin could flip Gold -> Silver between
     * two quick syncs. Within [TIER_STABILITY_WINDOW_MS] of the previous sync each surviving
     * coin keeps its stored tier (earned state and the displayed rarity % still refresh every
     * sync); after the window tiers recompute from fresh rarity. Platinum is detection-based,
     * never rarity-based, so it is always taken fresh. RA tiers come from fixed point values
     * and are never frozen.
     */
    private fun stabilizeTiers(
        provider: AchievementProvider,
        storedSet: AccountAchievementSetEntity?,
        storedCoins: List<AccountAchievementEntity>,
        fresh: List<SyncedCoin>,
        now: Long,
    ): List<SyncedCoin> {
        when (provider) {
            AchievementProvider.RETRO_ACHIEVEMENTS -> return fresh
            AchievementProvider.STEAM, AchievementProvider.LOCAL_STEAM, AchievementProvider.VITA_TROPHY -> Unit
        }
        val lastSyncedAt = storedSet?.lastSyncedAt ?: return fresh
        if (now - lastSyncedAt >= TIER_STABILITY_WINDOW_MS) return fresh

        val storedTiers = storedCoins.associate { it.providerAchievementId to it.tier }
        return fresh.map { coin ->
            if (coin.tier == ShibaTier.PLATINUM) return@map coin
            val stored = storedTiers[coin.providerAchievementId]
                ?.let { runCatching { ShibaTier.valueOf(it) }.getOrNull() }
                ?.takeIf { it != ShibaTier.PLATINUM }
            if (stored != null) coin.copy(tier = stored) else coin
        }
    }

    private fun earnedStateOf(stored: List<AccountAchievementEntity>): Set<Triple<String, Boolean, Long?>> =
        stored.map { Triple(it.providerAchievementId, it.isEarned, it.earnedAt) }.toSet()

    @JvmName("earnedStateOfSynced")
    private fun earnedStateOf(fresh: List<SyncedCoin>): Set<Triple<String, Boolean, Long?>> =
        fresh.map { Triple(it.providerAchievementId, it.isEarned, it.earnedAt) }.toSet()
}

/** Re-tier Steam coins from fresh rarity only when the last sync is at least this old (7 days). */
private const val TIER_STABILITY_WINDOW_MS = 7L * 24 * 60 * 60 * 1_000

private fun SyncedCoin.toEntity(provider: AchievementProvider, providerGameId: String) = AccountAchievementEntity(
    provider = provider.name,
    providerGameId = providerGameId,
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

// Title and icon are caller-owned identity (library game name vs provider name) and are set
// via copy() on the returned summary.
private fun summaryOf(
    provider: AchievementProvider,
    providerGameId: String,
    coins: List<SyncedCoin>,
    now: Long,
): AccountAchievementSetEntity {
    fun count(tier: ShibaTier, earnedOnly: Boolean) =
        coins.count { it.tier == tier && (!earnedOnly || it.isEarned) }
    // A provider-declared Platinum ("unlock every achievement"-style Steam coin) IS the crown:
    // earning it lights mastery, and it never appears in the Bronze/Silver/Gold tallies (its XP is
    // the crown's 300, not a per-coin value). Without one, the crown stays 100% completion —
    // hardcore for RA, any unlock for Steam (earnedHardcore mirrors isEarned there).
    val platinumCoins = coins.filter { it.tier == ShibaTier.PLATINUM }
    val mastered = if (platinumCoins.isNotEmpty()) platinumCoins.any { it.earnedHardcore }
                   else coins.isNotEmpty() && coins.all { it.earnedHardcore }
    return AccountAchievementSetEntity(
        provider = provider.name,
        providerGameId = providerGameId,
        title = "",
        bronzeTotal = count(ShibaTier.BRONZE, earnedOnly = false),
        silverTotal = count(ShibaTier.SILVER, earnedOnly = false),
        goldTotal = count(ShibaTier.GOLD, earnedOnly = false),
        bronzeEarned = count(ShibaTier.BRONZE, earnedOnly = true),
        silverEarned = count(ShibaTier.SILVER, earnedOnly = true),
        goldEarned = count(ShibaTier.GOLD, earnedOnly = true),
        mastered = mastered,
        lastSyncedAt = now,
    )
}
