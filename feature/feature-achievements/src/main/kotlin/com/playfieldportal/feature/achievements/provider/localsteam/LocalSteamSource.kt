package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.RateLimiter
import com.playfieldportal.feature.achievements.provider.RemoteAchievementSource
import com.playfieldportal.feature.achievements.provider.steam.SteamCoinMapper
import com.playfieldportal.feature.achievements.provider.steam.SteamPlayerAchievement
import com.playfieldportal.feature.achievements.provider.steam.SteamWebApi
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The LOCAL_STEAM provider: earned state from the GSE emulator's progress file in the game
 * folder, names/descriptions/icons from the Steam schema, rarity from the keyless global
 * percentages, tiers through [SteamCoinMapper]'s rules unchanged. Display-only — PFP reads what
 * the game already wrote; nothing here touches the emulator or the network beyond the same
 * read-only Steam Web API the STEAM provider uses.
 *
 * Hidden-description enrichment can't use the STEAM path (which reads the user's OWN profile page,
 * absent for an unowned emu copy), so it goes through [LocalSteamHiddenDescriptions] instead —
 * reading a roster of public top-owner profiles. Best-effort and never fatal.
 */
@Singleton
class LocalSteamSource @Inject constructor(
    private val discovery: LocalSteamDiscovery,
    private val webApi: SteamWebApi,
    private val credentials: AchievementCredentialsProvider,
    private val hiddenDescriptions: LocalSteamHiddenDescriptions,
) : RemoteAchievementSource {

    private val rate = RateLimiter(1_100)

    override suspend fun fetch(providerGameId: String): ProviderSyncResult {
        val key = credentials.steamApiKey()?.takeIf { it.isNotBlank() }
            ?: return ProviderSyncResult.MissingCredentials
        val game = discovery.findByAppId(providerGameId)
            ?: return ProviderSyncResult.Failed("no emu game folder for appid $providerGameId")

        // No progress file just means nothing earned YET (the emu's save redirect isn't set, or
        // the game hasn't been played) — the set still tracks at 0%, it never fails the sync.
        val earnedByName = game.achievementsUri
            ?.let { uri ->
                discovery.readProgress(uri).associate {
                    it.apiName to SteamPlayerAchievement(it.apiName, if (it.earned) 1 else 0, it.earnedAtEpochSeconds ?: 0)
                }
            }
            .orEmpty()

        rate.await()
        val schema = runCatching { webApi.getSchemaForGame(key, providerGameId) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                return ProviderSyncResult.Failed("schema request failed")
            }
        val schemaCoins = schema.body()?.game?.availableGameStats?.achievements.orEmpty()
        if (schemaCoins.isEmpty()) return ProviderSyncResult.NotFound

        // Rarity is best-effort, exactly as the STEAM provider treats it.
        rate.await()
        val percentByName = runCatching { webApi.getGlobalAchievementPercentages(providerGameId) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                null
            }
            ?.body()?.achievementpercentages?.achievements
            ?.associate { it.name to it.percent }
            .orEmpty()

        val coins = SteamCoinMapper.map(providerGameId, schemaCoins, percentByName, earnedByName)
        return ProviderSyncResult.Success(
            providerGameId,
            hiddenDescriptions.enrich(providerGameId, coins),
        )
    }
}
