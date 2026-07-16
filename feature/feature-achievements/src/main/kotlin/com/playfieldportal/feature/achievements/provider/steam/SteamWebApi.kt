package com.playfieldportal.feature.achievements.provider.steam

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * The Steam Web API (api.steampowered.com), read-only. The user's own API key rides as the `key`
 * query parameter on authenticated calls — which is exactly why the OkHttp client behind this
 * interface installs no logging interceptor (see [SteamClientModule]) and why no raw exception
 * message (which can embed the full request URL) is ever surfaced by callers.
 *
 * Calls return [Response] so status codes (e.g. the 403 a private profile produces) map to typed
 * results instead of exceptions.
 */
interface SteamWebApi {

    /** A game's achievement schema: names, descriptions, icons, hidden flags. */
    @GET("ISteamUserStats/GetSchemaForGame/v2/")
    suspend fun getSchemaForGame(
        @Query("key") key: String,
        @Query("appid") appId: String,
    ): Response<SteamSchemaResponse>

    /** Global unlock percentages (rarity); no key required. */
    @GET("ISteamUserStats/GetGlobalAchievementPercentagesForApp/v2/")
    suspend fun getGlobalAchievementPercentages(
        @Query("gameid") gameId: String,
    ): Response<SteamGlobalResponse>

    /** The user's earned achievements for a game. 403 = profile's Game Details are not public. */
    @GET("ISteamUserStats/GetPlayerAchievements/v1/")
    suspend fun getPlayerAchievements(
        @Query("key") key: String,
        @Query("steamid") steamId: String,
        @Query("appid") appId: String,
    ): Response<SteamPlayerResponse>

    /** Resolves a vanity profile name to a SteamID64. */
    @GET("ISteamUser/ResolveVanityURL/v1/")
    suspend fun resolveVanityUrl(
        @Query("key") key: String,
        @Query("vanityurl") vanity: String,
    ): Response<SteamVanityResponse>

    /**
     * Every game the account owns, with name and total playtime (minutes). Empty `response`
     * object when the profile's Game Details are not public. Field names verified against
     * Valve's WebAPI/GetOwnedGames documentation (2026-07-15).
     */
    @GET("IPlayerService/GetOwnedGames/v1/")
    suspend fun getOwnedGames(
        @Query("key") key: String,
        @Query("steamid") steamId: String,
        @Query("include_appinfo") includeAppInfo: Int = 1,
        @Query("include_played_free_games") includePlayedFreeGames: Int = 1,
    ): Response<SteamOwnedGamesResponse>
}

// ── Response models ───────────────────────────────────────────────────────────

@Serializable
data class SteamSchemaResponse(val game: SteamGame? = null)

@Serializable
data class SteamGame(
    @SerialName("availableGameStats") val availableGameStats: SteamGameStats? = null,
)

@Serializable
data class SteamGameStats(
    val achievements: List<SteamSchemaAchievement> = emptyList(),
    val stats: List<SteamSchemaStat> = emptyList(),
)

/** One stat definition from the schema. The Web API carries no int/float type information. */
@Serializable
data class SteamSchemaStat(
    val name: String,
    @SerialName("defaultvalue") val defaultValue: Double = 0.0,
)

@Serializable
data class SteamSchemaAchievement(
    val name: String,
    @SerialName("displayName") val displayName: String? = null,
    val description: String? = null,
    val hidden: Int = 0,
    val icon: String? = null,
    val icongray: String? = null,
)

@Serializable
data class SteamGlobalResponse(val achievementpercentages: SteamGlobalWrap? = null)

@Serializable
data class SteamGlobalWrap(val achievements: List<SteamGlobalPct> = emptyList())

@Serializable
data class SteamGlobalPct(val name: String, val percent: Double = 0.0)

@Serializable
data class SteamPlayerResponse(val playerstats: SteamPlayerStats? = null)

@Serializable
data class SteamPlayerStats(
    val success: Boolean = false,
    val error: String? = null,
    val achievements: List<SteamPlayerAchievement> = emptyList(),
)

@Serializable
data class SteamPlayerAchievement(
    val apiname: String,
    val achieved: Int = 0,
    val unlocktime: Long = 0,
)

@Serializable
data class SteamVanityResponse(val response: SteamVanityInner? = null)

@Serializable
data class SteamVanityInner(val steamid: String? = null, val success: Int = 0)

@Serializable
data class SteamOwnedGamesResponse(val response: SteamOwnedGamesInner? = null)

@Serializable
data class SteamOwnedGamesInner(
    @SerialName("game_count") val gameCount: Int = 0,
    val games: List<SteamOwnedGame> = emptyList(),
)

@Serializable
data class SteamOwnedGame(
    val appid: Long,
    val name: String? = null,
    @SerialName("playtime_forever") val playtimeForever: Long = 0,
)
