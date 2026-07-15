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
 * The hidden-description community-page enrichment is deliberately absent: it reads the USER'S
 * OWN profile page for the game, which only exists for owned copies (decision recorded in
 * docs/local-steam-achievements-plan.md section 8).
 */
@Singleton
class LocalSteamSource @Inject constructor(
    private val discovery: LocalSteamDiscovery,
    private val webApi: SteamWebApi,
    private val credentials: AchievementCredentialsProvider,
) : RemoteAchievementSource {

    private val rate = RateLimiter(1_100)

    override suspend fun fetch(providerGameId: String): ProviderSyncResult {
        val key = credentials.steamApiKey()?.takeIf { it.isNotBlank() }
            ?: return ProviderSyncResult.MissingCredentials
        val game = discovery.findByAppId(providerGameId)
            ?: return ProviderSyncResult.Failed("no emu game folder for appid $providerGameId")
        val progressUri = game.achievementsUri
            ?: return ProviderSyncResult.Failed("no local save data — the emu's save redirect is not set")

        val earnedByName = discovery.readProgress(progressUri)
            .associate { it.apiName to SteamPlayerAchievement(it.apiName, if (it.earned) 1 else 0, it.earnedAtEpochSeconds ?: 0) }

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

        return ProviderSyncResult.Success(
            providerGameId,
            SteamCoinMapper.map(providerGameId, schemaCoins, percentByName, earnedByName),
        )
    }
}
