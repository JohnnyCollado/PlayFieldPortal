package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.data.database.dao.AccountAchievementDao
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.provider.steam.SteamCoinMapper
import com.playfieldportal.feature.achievements.provider.steam.SteamMetadata
import com.playfieldportal.feature.achievements.provider.steam.SteamMetadataStore
import com.playfieldportal.feature.achievements.provider.steam.SteamPlayerResult
import com.playfieldportal.feature.achievements.provider.steam.SteamRemoteDataSource
import com.playfieldportal.feature.achievements.provider.steam.SteamSchemaResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Full-detail fetch for a tracked STEAM game with schema and rarity cached apart from the player's
 * unlocks (plan Task 7). Only Valve-documented Web API methods on the public host.
 *
 * Metadata is (re)requested on a first fetch, on an explicit per-game refresh, or when the game a
 * user is viewing has metadata older than [METADATA_MAX_AGE_MS]. A routine changed-playtime
 * refresh never renews it, so metadata ageing can't become a request for every installed title
 * at once — it costs one GetPlayerAchievements call.
 */
@Singleton
class SteamDetailFetcher @Inject constructor(
    private val steam: SteamRemoteDataSource,
    private val metadataStore: SteamMetadataStore,
    private val coinDao: AccountAchievementDao,
    private val clock: AchievementClock,
) {
    suspend fun fetch(appId: String, reason: FetchReason): ProviderSyncResult {
        val now = clock.now()
        val cached = metadataStore.get(appId)
        val renew = when (reason) {
            FetchReason.ROUTINE -> cached == null
            FetchReason.NEW_MATCH -> cached == null || now - cached.fetchedAt > METADATA_MAX_AGE_MS
            FetchReason.EXPLICIT -> true
            FetchReason.STALE_ON_OPEN -> cached == null || now - cached.fetchedAt > METADATA_MAX_AGE_MS
        }
        val metadata = if (renew) {
            when (val schema = steam.fetchSchema(appId)) {
                is SteamSchemaResult.Success -> SteamMetadata(
                    schema = schema.achievements,
                    rarity = steam.fetchGlobalRarity(appId) ?: cached?.rarity.orEmpty(),
                    fetchedAt = now,
                ).also { metadataStore.put(appId, it) }
                SteamSchemaResult.NotFound -> return ProviderSyncResult.NotFound
                SteamSchemaResult.MissingCredentials -> return ProviderSyncResult.MissingCredentials
                is SteamSchemaResult.Failed -> return ProviderSyncResult.Failed(schema.reason)
            }
        } else {
            cached!!
        }

        val earnedByName = when (val player = steam.fetchPlayerAchievements(appId)) {
            is SteamPlayerResult.Success -> player.byName
            SteamPlayerResult.ProfileNotPublic -> return ProviderSyncResult.ProfileNotPublic
            SteamPlayerResult.MissingCredentials -> return ProviderSyncResult.MissingCredentials
            is SteamPlayerResult.Failed -> return ProviderSyncResult.Failed(player.reason)
        }

        val mapped = SteamCoinMapper.map(appId, metadata.schema, metadata.rarity, earnedByName)
        // A hidden coin's description, once learned, is kept (the writer carries it forward);
        // the community page is read only for a newly earned hidden coin nobody has described.
        val known = coinDao.getForSet(AchievementProvider.STEAM.name, appId)
            .filter { it.description.isNotBlank() }
            .associate { it.providerAchievementId to it.description }
        val carried = mapped.map { coin ->
            if (coin.description.isBlank()) known[coin.providerAchievementId]?.let { coin.copy(description = it) } ?: coin
            else coin
        }
        val coins = if (carried.any { it.isHidden && it.isEarned && it.description.isBlank() }) {
            steam.enrichHiddenDescriptions(appId, carried)
        } else {
            carried
        }
        return ProviderSyncResult.Success(appId, coins)
    }

    companion object {
        /** Viewed games renew schema and rarity once their cache is older than this (30 days). */
        const val METADATA_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1_000
    }
}
