package com.playfieldportal.feature.artwork.api

import com.playfieldportal.feature.artwork.MetadataApiKeyProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// ── Response models ────────────────────────────────────────────────────────────

@Serializable
data class IgdbTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in")   val expiresIn: Long,
    @SerialName("token_type")   val tokenType: String = "bearer",
)

@Serializable
data class IgdbGame(
    val id: Long,
    val name: String? = null,
    val cover: IgdbImage? = null,
    val artworks: List<IgdbImage> = emptyList(),
)

@Serializable
data class IgdbImage(
    val id: Long = 0,
    @SerialName("image_id") val imageId: String? = null,
)

// ── Parsed result ──────────────────────────────────────────────────────────────

data class IgdbGameInfo(
    val artworkUrl: String?,   // cover → box art proxy
    val heroUrl: String?,      // first artwork image → hero proxy
    val logoUrl: String?,      // IGDB has no clear logos, always null
)

// ── Token cache ────────────────────────────────────────────────────────────────

private data class IgdbToken(val accessToken: String, val expiresAtMs: Long)

// ── API client ─────────────────────────────────────────────────────────────────

@Singleton
class IgdbApi @Inject constructor(
    private val httpClient: HttpClient,
    private val keyProvider: MetadataApiKeyProvider,
) {
    // In-memory token cache — valid for the process lifetime.
    // Token TTL from Twitch is ~60 days; we re-fetch 60s before expiry.
    private var cachedToken: IgdbToken? = null

    suspend fun hasCredentials(): Boolean = keyProvider.hasIgdbCredentials()

    suspend fun fetchGameInfo(platformId: String, title: String): IgdbGameInfo? {
        val clientId = keyProvider.getIgdbClientId() ?: return null
        val clientSecret = keyProvider.getIgdbClientSecret() ?: return null
        val token = obtainToken(clientId, clientSecret) ?: return null

        return try {
            val games: List<IgdbGame> = httpClient.post("$BASE/games") {
                header("Client-ID", clientId)
                header("Authorization", "Bearer ${token.accessToken}")
                contentType(ContentType.Text.Plain)
                // Apicalypse query — title search, request cover + artworks
                setBody("""search "${title.replace("\"", "'")}"; fields name,cover.image_id,artworks.image_id; limit 1;""")
            }.body()

            val game = games.firstOrNull() ?: return null
            IgdbGameInfo(
                artworkUrl = game.cover?.imageId?.let { coverImageUrl(it) },
                heroUrl    = game.artworks.firstOrNull()?.imageId?.let { artworkImageUrl(it) },
                logoUrl    = null,
            )
        } catch (e: Exception) {
            Timber.w(e, "IGDB fetch failed for '$title'")
            null
        }
    }

    /** Test credentials without caching the resulting token. */
    suspend fun testCredentials(clientId: String, clientSecret: String): Boolean = try {
        val response: IgdbTokenResponse = httpClient.post("$AUTH_BASE/token") {
            parameter("client_id",     clientId)
            parameter("client_secret", clientSecret)
            parameter("grant_type",    "client_credentials")
        }.body()
        response.accessToken.isNotBlank()
    } catch (e: Exception) {
        Timber.w(e, "IGDB credential test failed")
        false
    }

    private suspend fun obtainToken(clientId: String, clientSecret: String): IgdbToken? {
        val cached = cachedToken
        if (cached != null && cached.expiresAtMs > System.currentTimeMillis()) return cached

        return try {
            val response: IgdbTokenResponse = httpClient.post("$AUTH_BASE/token") {
                parameter("client_id",     clientId)
                parameter("client_secret", clientSecret)
                parameter("grant_type",    "client_credentials")
            }.body()
            val expiresAt = System.currentTimeMillis() + (response.expiresIn - 60L) * 1_000L
            IgdbToken(response.accessToken, expiresAt).also { cachedToken = it }
        } catch (e: Exception) {
            Timber.w(e, "IGDB token fetch failed")
            null
        }
    }

    companion object {
        private const val BASE      = "https://api.igdb.com/v4"
        private const val AUTH_BASE = "https://id.twitch.tv/oauth2"

        fun coverImageUrl(imageId: String)   = "https://images.igdb.com/igdb/image/upload/t_cover_big/$imageId.jpg"
        fun artworkImageUrl(imageId: String) = "https://images.igdb.com/igdb/image/upload/t_screenshot_big/$imageId.jpg"
    }
}
