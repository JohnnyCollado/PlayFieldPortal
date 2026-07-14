package com.playfieldportal.feature.achievements.api

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.domain.achievement.ShibaTier
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

// ── Response models ───────────────────────────────────────────────────────────

@Serializable
private data class SteamSchemaResponse(val game: SteamGame? = null)

@Serializable
private data class SteamGame(
    @SerialName("availableGameStats") val availableGameStats: SteamGameStats? = null,
)

@Serializable
private data class SteamGameStats(val achievements: List<SteamSchemaAchievement> = emptyList())

@Serializable
private data class SteamSchemaAchievement(
    val name: String,
    @SerialName("displayName") val displayName: String? = null,
    val description: String? = null,
    val hidden: Int = 0,
    val icon: String? = null,
    val icongray: String? = null,
)

@Serializable
private data class SteamGlobalResponse(val achievementpercentages: SteamGlobalWrap? = null)

@Serializable
private data class SteamGlobalWrap(val achievements: List<SteamGlobalPct> = emptyList())

@Serializable
private data class SteamGlobalPct(val name: String, val percent: Double = 0.0)

@Serializable
private data class SteamPlayerResponse(val playerstats: SteamPlayerStats? = null)

@Serializable
private data class SteamPlayerStats(
    val success: Boolean = false,
    val error: String? = null,
    val achievements: List<SteamPlayerAchievement> = emptyList(),
)

@Serializable
private data class SteamPlayerAchievement(
    val apiname: String,
    val achieved: Int = 0,
    val unlocktime: Long = 0,
)

@Serializable
private data class SteamVanityResponse(val response: SteamVanityInner? = null)

@Serializable
private data class SteamVanityInner(val steamid: String? = null, val success: Int = 0)

// ── API client ────────────────────────────────────────────────────────────────

private const val BASE = "https://api.steampowered.com"

/**
 * Read-only Steam Web API client. Uses the user's own API key (never one of ours) plus their
 * SteamID64, both from [AchievementCredentialsProvider]. No password is ever handled. Coin tiers
 * come from the global unlock percentage; the Platinum crown is minted locally on 100%.
 */
@Singleton
class SteamAchievementsApi @Inject constructor(
    @AchievementsHttpClient private val client: HttpClient,
    private val credentials: AchievementCredentialsProvider,
) {
    private val rate = RateLimiter(1_100)

    /** Resolves a vanity name to a SteamID64, or null if it can't be resolved. */
    suspend fun resolveVanity(vanity: String): String? {
        val key = credentials.steamApiKey()?.takeIf { it.isNotBlank() } ?: return null
        rate.await()
        return runCatching {
            client.get("$BASE/ISteamUser/ResolveVanityURL/v1/") {
                parameter("key", key)
                parameter("vanityurl", vanity.trim())
            }.body<SteamVanityResponse>().response?.takeIf { it.success == 1 }?.steamid
        }.getOrNull()
    }

    /** Fetches a game's coins for the configured user. [appId] is the Steam appid. */
    suspend fun fetch(appId: String): ProviderSyncResult {
        val key = credentials.steamApiKey()?.takeIf { it.isNotBlank() }
            ?: return ProviderSyncResult.MissingCredentials
        val steamId = credentials.steamId64()?.takeIf { it.isNotBlank() }
            ?: return ProviderSyncResult.MissingCredentials

        val schema = runCatching {
            rate.await()
            client.get("$BASE/ISteamUserStats/GetSchemaForGame/v2/") {
                parameter("key", key)
                parameter("appid", appId)
            }.body<SteamSchemaResponse>()
        }.getOrElse { return ProviderSyncResult.Failed("schema request failed") }

        val schemaCoins = schema.game?.availableGameStats?.achievements.orEmpty()
        if (schemaCoins.isEmpty()) return ProviderSyncResult.NotFound

        val percentByName = runCatching {
            rate.await()
            client.get("$BASE/ISteamUserStats/GetGlobalAchievementPercentagesForApp/v2/") {
                parameter("gameid", appId)
            }.body<SteamGlobalResponse>()
        }.getOrNull()?.achievementpercentages?.achievements
            ?.associate { it.name to it.percent }
            .orEmpty()

        rate.await()
        val playerHttp = runCatching {
            client.get("$BASE/ISteamUserStats/GetPlayerAchievements/v1/") {
                parameter("key", key)
                parameter("steamid", steamId)
                parameter("appid", appId)
            }
        }.getOrElse { return ProviderSyncResult.Failed("player request failed") }

        val stats = runCatching { playerHttp.body<SteamPlayerResponse>() }.getOrNull()?.playerstats
        if (playerHttp.status.value == 403 ||
            (stats?.success == false && stats.error?.contains("not public", ignoreCase = true) == true)
        ) {
            return ProviderSyncResult.ProfileNotPublic
        }

        val earnedByName = stats?.achievements?.associateBy { it.apiname }.orEmpty()

        val coins = schemaCoins.map { a ->
            val percent = percentByName[a.name] ?: 0.0
            val earned = earnedByName[a.name]
            val isEarned = earned?.achieved == 1
            SyncedCoin(
                providerAchievementId = a.name,
                title = a.displayName ?: a.name,
                description = a.description.orEmpty(),
                tier = ShibaTier.forRarity(percent),
                globalRarity = percent,
                iconUrl = if (isEarned) a.icon else (a.icongray ?: a.icon),
                isHidden = a.hidden == 1,
                isEarned = isEarned,
                // Steam has no hardcore/softcore split: any unlock counts toward the crown.
                earnedHardcore = isEarned,
                // Steam unlocktime is epoch seconds; store millis. 0 = not earned.
                earnedAt = earned?.unlocktime?.takeIf { it > 0 }?.times(1_000),
            )
        }
        return ProviderSyncResult.Success(appId, coins)
    }
}
