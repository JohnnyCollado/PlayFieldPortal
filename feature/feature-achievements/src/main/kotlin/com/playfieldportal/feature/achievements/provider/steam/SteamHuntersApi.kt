package com.playfieldportal.feature.achievements.provider.steam

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Streaming

/**
 * Steam Hunters' public achievement list (steamhunters.com): keyless JSON that, unlike Steam's Web
 * API schema, carries the descriptions of HIDDEN achievements. Undocumented, so callers treat it as
 * a best-effort source with a fallback.
 *
 * Returns the raw, streamed body rather than a converter-decoded list so the caller can cap the
 * read: the endpoint answers gzip + chunked, with no Content-Length to check up front, and without
 * `@Streaming` Retrofit would buffer the whole body before any cap ran. Decode with
 * [SteamHuntersAchievements.decode]. Security posture: only the appid is sent, over HTTPS, and every
 * field is treated as untrusted display text.
 */
interface SteamHuntersApi {

    @Streaming
    @Headers("User-Agent: PlayFieldPortal")
    @GET("api/apps/{appId}/achievements")
    suspend fun achievements(@Path("appId") appId: String): Response<ResponseBody>
}

/** One Steam Hunters entry; [apiName] is Steam's achievement `name`, the same key the schema uses. */
@Serializable
data class SteamHuntersAchievement(
    val apiName: String? = null,
    val name: String? = null,
    val description: String? = null,
)

object SteamHuntersAchievements {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    /** Decodes a response body; an unknown game is `[]`. Throws on anything that isn't the list. */
    fun decode(body: String): List<SteamHuntersAchievement> = json.decodeFromString(body)
}
