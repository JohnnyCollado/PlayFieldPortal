package com.playfieldportal.feature.achievements.provider.steam

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.RateLimiter
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Steam remote data source, built on the [SteamWebApi] Retrofit interface. Read-only: the
 * user's own API key + SteamID64 come from [AchievementCredentialsProvider]; no password is ever
 * handled. Error paths map to typed [ProviderSyncResult]s — raw exception messages are never
 * surfaced, since they can embed the request URL (which carries the key).
 */
/** One owned game from the account's GetOwnedGames list. */
data class SteamOwnedEntry(
    val appId: String,
    val name: String,
    val playtimeForeverMinutes: Long,
)

/** Outcome of fetching the account's owned-games list. */
sealed interface SteamOwnedGamesResult {
    data class Success(val entries: List<SteamOwnedEntry>) : SteamOwnedGamesResult
    data object MissingCredentials : SteamOwnedGamesResult

    /** Steam answered but withheld the list — the profile's Game Details are not public. */
    data object ProfileNotPublic : SteamOwnedGamesResult
    data class Failed(val reason: String) : SteamOwnedGamesResult
}

@Singleton
class SteamRemoteDataSource @Inject constructor(
    private val webApi: SteamWebApi,
    private val communityApi: SteamCommunityApi,
    private val credentials: AchievementCredentialsProvider,
) {
    private val rate = RateLimiter(1_100)

    /** Resolves a vanity name to a SteamID64, or null if it can't be resolved. */
    suspend fun resolveVanity(vanity: String): String? {
        val key = credentials.steamApiKey()?.takeIf { it.isNotBlank() } ?: return null
        rate.await()
        return runCatching { webApi.resolveVanityUrl(key, vanity.trim()) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                return null
            }
            .body()?.response?.takeIf { it.success == 1 }?.steamid
    }

    /**
     * The account's whole owned-games list (played free games included), one call. A public
     * profile with hidden Game Details answers with an EMPTY response object — mapped to
     * [SteamOwnedGamesResult.ProfileNotPublic], never to an empty library.
     */
    suspend fun ownedGames(): SteamOwnedGamesResult {
        val key = credentials.steamApiKey()?.takeIf { it.isNotBlank() }
            ?: return SteamOwnedGamesResult.MissingCredentials
        val steamId = credentials.steamId64()?.takeIf { it.isNotBlank() }
            ?: return SteamOwnedGamesResult.MissingCredentials

        rate.await()
        val response = runCatching { webApi.getOwnedGames(key, steamId) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                return SteamOwnedGamesResult.Failed("network error")
            }
        if (response.code() == 401 || response.code() == 403) {
            return SteamOwnedGamesResult.Failed("Steam rejected the API key")
        }
        val inner = response.body()?.response
            ?: return SteamOwnedGamesResult.Failed("Steam returned ${response.code()}")
        if (inner.gameCount == 0 && inner.games.isEmpty()) return SteamOwnedGamesResult.ProfileNotPublic

        return SteamOwnedGamesResult.Success(
            inner.games.map {
                SteamOwnedEntry(
                    appId = it.appid.toString(),
                    name = it.name.orEmpty(),
                    playtimeForeverMinutes = it.playtimeForever,
                )
            },
        )
    }

    /** Fetches a game's coins for the configured user. [appId] is the Steam appid. */
    suspend fun fetch(appId: String): ProviderSyncResult {
        val key = credentials.steamApiKey()?.takeIf { it.isNotBlank() }
            ?: return ProviderSyncResult.MissingCredentials
        val steamId = credentials.steamId64()?.takeIf { it.isNotBlank() }
            ?: return ProviderSyncResult.MissingCredentials

        rate.await()
        val schema = runCatching { webApi.getSchemaForGame(key, appId) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                return ProviderSyncResult.Failed("schema request failed")
            }
        val schemaCoins = schema.body()?.game?.availableGameStats?.achievements.orEmpty()
        if (schemaCoins.isEmpty()) return ProviderSyncResult.NotFound

        // Rarity is best-effort: a failed percentages call still syncs coins (rarity 0).
        rate.await()
        val percentByName = runCatching { webApi.getGlobalAchievementPercentages(appId) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                null
            }
            ?.body()?.achievementpercentages?.achievements
            ?.associate { it.name to it.percent }
            .orEmpty()

        rate.await()
        val player = runCatching { webApi.getPlayerAchievements(key, steamId, appId) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                return ProviderSyncResult.Failed("player request failed")
            }
        val stats = player.body()?.playerstats
        if (player.code() == 403 ||
            (stats?.success == false && stats.error?.contains("not public", ignoreCase = true) == true)
        ) {
            return ProviderSyncResult.ProfileNotPublic
        }

        val earnedByName = stats?.achievements?.associateBy { it.apiname }.orEmpty()
        val coins = SteamCoinMapper.map(appId, schemaCoins, percentByName, earnedByName)
        return ProviderSyncResult.Success(appId, enrichHiddenDescriptions(appId, steamId, coins))
    }

    /**
     * Fills in descriptions for EARNED hidden achievements, which the Web API withholds forever,
     * from the user's own public community achievements page (the one place Steam shows them).
     * Strictly best-effort: only runs when such coins exist, and any failure — network, a private
     * page, a Valve markup change, an ambiguous title — returns the coins untouched, leaving the
     * UI's "Steam keeps this one's description secret" fallback in place. Never fails the sync.
     */
    private suspend fun enrichHiddenDescriptions(
        appId: String,
        steamId: String,
        coins: List<com.playfieldportal.feature.achievements.api.SyncedCoin>,
    ): List<com.playfieldportal.feature.achievements.api.SyncedCoin> {
        if (coins.none { it.isHidden && it.isEarned && it.description.isBlank() }) return coins
        return runCatching {
            rate.await()
            val response = communityApi.achievementsPage(steamId, appId)
            // Soft size cap: an achievements page is a few hundred KB; anything huge is not the
            // page we expect, so skip rather than parse it.
            val body = response.body()?.takeIf { it.contentLength() <= 4_000_000 }?.string()
                ?: return coins
            val descriptionByTitle = SteamCommunityAchievementsParser.parse(body)
            coins.map { coin ->
                if (!coin.isHidden || !coin.isEarned || coin.description.isNotBlank()) return@map coin
                val found = descriptionByTitle[SteamCommunityAchievementsParser.normalizeTitle(coin.title)]
                if (found != null) coin.copy(description = found) else coin
            }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            coins
        }
    }
}
