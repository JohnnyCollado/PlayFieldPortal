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
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
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
    // Unix seconds. Requested only by searchGames, where it tells two same-named editions apart.
    @SerialName("first_release_date") val firstReleaseDate: Long? = null,
    val summary: String? = null,
    val genres: List<IgdbNamed> = emptyList(),
    @SerialName("involved_companies") val involvedCompanies: List<IgdbInvolvedCompany> = emptyList(),
    // IGDB's own 0..100 score, critic and user combined. Normalized to 0..1 on the way out, the
    // same scale ScreenScraper's /20 note is normalized to.
    @SerialName("total_rating") val totalRating: Double? = null,
)

@Serializable
data class IgdbImage(
    val id: Long = 0,
    @SerialName("image_id") val imageId: String? = null,
)

@Serializable
data class IgdbNamed(
    val id: Long = 0,
    val name: String? = null,
)

/**
 * One company's role on a game. IGDB models this as a join row rather than two name lists, so the
 * same company can be both developer and publisher and is reported once with both flags set.
 */
@Serializable
data class IgdbInvolvedCompany(
    val id: Long = 0,
    val company: IgdbNamed? = null,
    val developer: Boolean = false,
    val publisher: Boolean = false,
)

@Serializable
data class IgdbExternalGame(
    val id: Long = 0,
    /** The IGDB game this store entry points at. */
    val game: Long? = null,
    val uid: String? = null,
)

// ── Parsed result ──────────────────────────────────────────────────────────────

data class IgdbGameInfo(
    val artworkUrl: String?,   // cover → box art proxy
    val heroUrl: String?,      // first artwork image → hero proxy
    val logoUrl: String?,      // IGDB has no clear logos, always null
    // Text metadata. Requested since C23 T5 — before that IGDB was an artwork-only source, which is
    // why the metadata preview never offered it a preset.
    val title: String? = null,
    val description: String? = null,
    val developer: String? = null,
    val publisher: String? = null,
    val releaseYear: Int? = null,
    val releaseDate: String? = null,
    val genre: String? = null,
    val communityRating: Float? = null,
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

    /** IGDB's single best title hit — the batch scraper's and the unmatched Studio browse's call. */
    suspend fun fetchGameInfo(platformId: String, title: String): IgdbGameInfo? =
        query(bestMatchBody(title), "'$title'")?.firstOrNull()?.toInfo()

    /**
     * Up to [limit] games for [title] (C16). IGDB's `search` is a real multi-result endpoint — what
     * the tiered matcher's Tier 3 and Change Match need. Not platform-scoped: the tree has no IGDB
     * platform-id table, so uniqueness is established on normalized title alone.
     */
    suspend fun searchGames(title: String, limit: Int = SEARCH_LIMIT): List<IgdbGame> =
        query(searchBody(title, limit), "search '$title'").orEmpty()

    /** The art of one known IGDB game — the Studio's browse once a match exists. */
    suspend fun fetchGameInfoById(igdbId: Long): IgdbGameInfo? =
        query(byIdBody(igdbId), "id $igdbId")?.firstOrNull()?.toInfo()

    private suspend fun query(body: String, what: String): List<IgdbGame>? =
        request("games", body, what)

    private fun IgdbGame.toInfo(): IgdbGameInfo {
        // atZone().toLocalDate(), not LocalDate.ofInstant: the latter is a Java 9 method Android
        // only carries from API 33, and this module ships to minSdk 29.
        val released = firstReleaseDate
            ?.let { Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC).toLocalDate() }
        return IgdbGameInfo(
            artworkUrl = cover?.imageId?.let { coverImageUrl(it) },
            heroUrl    = artworks.firstOrNull()?.imageId?.let { artworkImageUrl(it) },
            logoUrl    = null,
            title       = name,
            description = summary,
            // Every company in a role, joined — a game with three publishers reads as all three
            // rather than an arbitrary one. The same company can hold both roles.
            developer   = involvedCompanies.filter { it.developer }.companyNames(),
            publisher   = involvedCompanies.filter { it.publisher }.companyNames(),
            releaseYear = released?.year,
            // ISO yyyy-MM-dd in UTC, matching what ScreenScraper stores. IGDB's timestamp carries no
            // zone of its own, so reading it in anything but UTC would shift some dates by a day.
            releaseDate = released?.format(DateTimeFormatter.ISO_LOCAL_DATE),
            genre       = genres.mapNotNull { it.name?.takeIf(String::isNotBlank) }
                .distinct().joinToString(", ").takeIf { it.isNotBlank() },
            // 0..100 to 0..1, the scale every stored community rating uses.
            communityRating = totalRating?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) },
        )
    }

    private fun List<IgdbInvolvedCompany>.companyNames(): String? =
        mapNotNull { it.company?.name?.takeIf(String::isNotBlank) }
            .distinct().joinToString(", ").takeIf { it.isNotBlank() }

    /**
     * The IGDB game a storefront entry points at, by exact id — no title comparison anywhere in
     * this path (C23 T4). Null when the store is one IGDB does not map, or when IGDB has never
     * seen that id.
     */
    suspend fun fetchGameIdByStorefront(storefront: String, storefrontGameId: String): Long? {
        val category = EXTERNAL_GAME_CATEGORIES[storefront.uppercase()] ?: return null
        if (storefrontGameId.isBlank()) return null
        return request<IgdbExternalGame>(
            "external_games",
            externalGameBody(category, storefrontGameId),
            "$storefront:$storefrontGameId",
        )?.firstOrNull()?.game
    }

    private suspend inline fun <reified T> request(endpoint: String, body: String, what: String): List<T>? {
        val clientId = keyProvider.getIgdbClientId() ?: return null
        val clientSecret = keyProvider.getIgdbClientSecret() ?: return null
        val token = obtainToken(clientId, clientSecret) ?: return null

        return try {
            httpClient.post("$BASE/$endpoint") {
                header("Client-ID", clientId)
                header("Authorization", "Bearer ${token.accessToken}")
                contentType(ContentType.Text.Plain)
                setBody(body)
            }.body<List<T>>()
        } catch (e: CancellationException) {
            // A cancelled browse is not "IGDB has nothing". Swallowing it here is what let a source
            // switch cache an empty result page in the Artwork Studio.
            throw e
        } catch (e: Exception) {
            Timber.w(e, "IGDB $endpoint request failed for $what")
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "IGDB token fetch failed")
            null
        }
    }

    companion object {
        private const val BASE      = "https://api.igdb.com/v4"
        private const val AUTH_BASE = "https://id.twitch.tv/oauth2"
        private const val SEARCH_LIMIT = 10

        // Apicalypse bodies. Pure, so the query text is testable without a network client. A double
        // quote in a title becomes a single quote — it would otherwise close the search string.
        private fun quoted(title: String) = "\"" + title.replace("\"", "'") + "\""

        // Every field the metadata preset and the artwork browse together need. Asked for as one
        // list so a game costs one request whichever of the two wants it.
        private const val FULL_FIELDS =
            "name,summary,first_release_date,total_rating,genres.name," +
                "involved_companies.company.name,involved_companies.developer," +
                "involved_companies.publisher,cover.image_id,artworks.image_id"

        internal fun bestMatchBody(title: String) =
            "search ${quoted(title)}; fields $FULL_FIELDS; limit 1;"

        internal fun searchBody(title: String, limit: Int) =
            "search ${quoted(title)}; fields name,first_release_date,cover.image_id; limit $limit;"

        internal fun byIdBody(igdbId: Long) =
            "fields $FULL_FIELDS; where id = $igdbId;"

        internal fun externalGameBody(category: Int, uid: String) =
            "fields game,uid; where category = $category & uid = ${quoted(uid)}; limit 1;"

        /**
         * IGDB `external_games.category` values, read from IGDB's own API documentation
         * (api-docs.igdb.com, "External Game Enums") on 2026-09-24 — not from memory and not from a
         * blog post. Epic IS covered, so all three stores resolve by exact id.
         *
         * IGDB marks `category` DEPRECATED in favour of `external_game_source`. It is still the key
         * used here on purpose: the source ids live behind the `/external_game_sources` endpoint
         * and are published nowhere, so switching would trade a documented constant for an extra
         * round trip and an undocumented one. When IGDB publishes those ids, this map is the single
         * place that changes.
         *
         * AMAZON is absent deliberately: IGDB splits it three ways (amazon_asin 20, amazon_luna 22,
         * amazon_adg 23) and `games.storefront` does not say which, so a guess would be a WRONG
         * exact match — worse than falling back to a title search. CUSTOM_GAME has no store at all.
         */
        internal val EXTERNAL_GAME_CATEGORIES: Map<String, Int> = mapOf(
            "STEAM" to 1,
            "GOG" to 5,
            "EPIC" to 26,
        )

        fun coverThumbUrl(imageId: String)   = "https://images.igdb.com/igdb/image/upload/t_cover_small/$imageId.jpg"
        fun coverImageUrl(imageId: String)   = "https://images.igdb.com/igdb/image/upload/t_cover_big/$imageId.jpg"
        fun artworkImageUrl(imageId: String) = "https://images.igdb.com/igdb/image/upload/t_screenshot_big/$imageId.jpg"
    }
}
