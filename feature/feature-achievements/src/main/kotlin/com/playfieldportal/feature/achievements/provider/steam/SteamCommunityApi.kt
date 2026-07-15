package com.playfieldportal.feature.achievements.provider.steam

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The user's own Steam Community achievements page (steamcommunity.com). This is the only place
 * Steam exposes the descriptions of hidden achievements the user has EARNED — the Web API withholds
 * them permanently, even after unlock — so the sync reads the user's own public page to fill that
 * gap.
 *
 * Security posture: the URL carries only the public SteamID64 (never a key), the host is
 * Valve-owned over HTTPS, the response is treated strictly as untrusted display text (see
 * [SteamCommunityAchievementsParser]), and any failure leaves the coins exactly as the Web API
 * returned them.
 */
interface SteamCommunityApi {

    @Headers("User-Agent: Mozilla/5.0")
    @GET("profiles/{steamId64}/stats/{appId}/achievements")
    suspend fun achievementsPage(
        @Path("steamId64") steamId64: String,
        @Path("appId") appId: String,
        // English to match the schema's default-language titles, which the parser joins on.
        @Query("l") language: String = "english",
    ): Response<ResponseBody>
}
