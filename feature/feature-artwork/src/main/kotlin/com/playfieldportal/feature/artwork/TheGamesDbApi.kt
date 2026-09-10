package com.playfieldportal.feature.artwork

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

// ── Response models ────────────────────────────────────────────────────────────

@Serializable
data class TgdbGamesResponse(
    val code: Int = 0,
    val status: String = "",
    val data: TgdbGamesData? = null,
    val include: TgdbInclude? = null,
)

@Serializable
data class TgdbGamesData(
    val games: List<TgdbGame> = emptyList(),
    val count: Int = 0,
)

@Serializable
data class TgdbGame(
    val id: Long,
    @SerialName("game_title") val gameTitle: String,
    @SerialName("release_date") val releaseDate: String? = null,
    val platform: Int? = null,
    val overview: String? = null,
    val rating: String? = null,
)

@Serializable
data class TgdbInclude(
    val boxart: TgdbBoxartInclude? = null,
)

@Serializable
data class TgdbBoxartInclude(
    @SerialName("base_url") val baseUrl: TgdbBaseUrl? = null,
    val data: Map<String, List<TgdbImage>> = emptyMap(),
)

@Serializable
data class TgdbBaseUrl(
    val original: String = "",
    val large: String = "",
    val medium: String = "",
    val thumb: String = "",
)

@Serializable
data class TgdbImage(
    val id: Int,
    val type: String,     // "boxart", "fanart", "banner", "screenshot", "clearlogo"
    val side: String? = null, // "front", "back"
    val filename: String,
    val resolution: String? = null,
)

// ── Parsed result ──────────────────────────────────────────────────────────────

data class TgdbGameInfo(
    val tgdbId: Long,
    val title: String,
    val description: String?,
    val releaseYear: Int?,
    val artworkUrl: String?,
    val heroUrl: String?,
    val logoUrl: String?,
)

// ── API client ─────────────────────────────────────────────────────────────────

@Singleton
class TheGamesDbApi @Inject constructor(
    private val httpClient: HttpClient,
    private val keyProvider: MetadataApiKeyProvider,
) {
    companion object {
        private const val BASE = "https://api.thegamesdb.net/v1"

        // TheGamesDB platform IDs → our platform IDs
        val PLATFORM_IDS = mapOf(
            "psx"            to 10,
            "ps2"            to 11,
            "ps3"            to 12,
            "psp"            to 13,
            "psvita"         to 39,
            "nes"            to 7,
            "snes"           to 6,
            "n64"            to 3,
            "gb"             to 4,
            "gbc"            to 41,
            "gba"            to 5,
            "nds"            to 8,
            "n3ds"           to 37,
            "gc"             to 2,
            "wii"            to 9,
            "wiiu"           to 38,
            "switch"         to 4920,
            "virtualboy"     to 38,   // approximate
            "megadrive"      to 36,
            "mastersystem"   to 35,
            "gamegear"       to 21,
            "saturn"         to 17,
            "dreamcast"      to 16,
            "segacd"         to 78,
            "sega32x"        to 33,
            "atari2600"      to 22,
            "atari5200"      to 26,
            "atari7800"      to 27,
            "atarilynx"      to 61,
            "pcengine"       to 34,
            "neogeo"         to 24,
            "ngp"            to 82,
            "mame"           to 23,
            "wonderswan"     to 57,
            "wonderswancolor" to 58,
            "c64"            to 40,
        )

        /**
         * One game's text and art out of a Games response. Pure, so parsing is testable without a
         * network client. Images are read for [game]'s own id: a ByGameName response carries every
         * hit's images in one include block, and a matched game must never show another hit's box.
         */
        internal fun infoFrom(response: TgdbGamesResponse, game: TgdbGame): TgdbGameInfo {
            val baseUrl = response.include?.boxart?.baseUrl?.large
                ?: response.include?.boxart?.baseUrl?.original
                ?: ""
            val images = response.include?.boxart?.data?.get(game.id.toString()) ?: emptyList()

            return TgdbGameInfo(
                tgdbId      = game.id,
                title       = game.gameTitle,
                description = game.overview,
                releaseYear = game.releaseDate?.take(4)?.toIntOrNull(),
                artworkUrl  = images.firstOrNull { it.type == "boxart" && it.side == "front" }
                    ?.filename?.let { "$baseUrl$it" },
                heroUrl     = images.firstOrNull { it.type == "fanart" }
                    ?.filename?.let { "$baseUrl$it" },
                logoUrl     = images.firstOrNull { it.type == "clearlogo" }
                    ?.filename?.let { "$baseUrl$it" },
            )
        }
    }

    /** False until the user enters a key in Settings ▸ Artwork; every lookup returns nothing without one. */
    suspend fun hasApiKey(): Boolean = keyProvider.hasTgdbKey()

    /** The single best title hit on [platformId] — the batch scraper's and the unmatched Studio browse's call. */
    suspend fun fetchGameInfo(
        platformId: String,
        title: String,
    ): TgdbGameInfo? {
        val response = byGameName(platformId, title, withImages = true) ?: return null
        val game = response.data?.games?.firstOrNull() ?: return null
        return infoFrom(response, game)
    }

    /**
     * Every game TheGamesDB returns for [title] (C16) — what Tier 3 and Change Match need.
     * Filtered to [platformId] when it is in [PLATFORM_IDS]; an unmapped platform searches all.
     */
    suspend fun searchGames(platformId: String, title: String): List<TgdbGame> =
        byGameName(platformId, title, withImages = false)?.data?.games.orEmpty()

    /** One known game's text and art — the Studio's browse once a match exists. */
    suspend fun fetchGameInfoById(tgdbId: Long): TgdbGameInfo? {
        val response = request("Games/ByGameID", "id $tgdbId") {
            parameter("id", tgdbId)
            parameter("fields", "overview,release_date,rating")
            parameter("include", "boxart")
        } ?: return null
        val game = response.data?.games?.firstOrNull { it.id == tgdbId } ?: return null
        return infoFrom(response, game)
    }

    private suspend fun byGameName(platformId: String, title: String, withImages: Boolean): TgdbGamesResponse? {
        val tgdbPlatformId = PLATFORM_IDS[platformId]
        return request("Games/ByGameName", "'$title'") {
            parameter("name", title)
            parameter("fields", "overview,release_date,rating")
            if (withImages) parameter("include", "boxart")
            if (tgdbPlatformId != null) parameter("filter[platform]", tgdbPlatformId)
        }
    }

    private suspend fun request(
        path: String,
        what: String,
        params: HttpRequestBuilder.() -> Unit,
    ): TgdbGamesResponse? {
        val apiKey = keyProvider.getTgdbKey() ?: run {
            Timber.d("TheGamesDB: no API key configured")
            return null
        }
        return try {
            val response: TgdbGamesResponse = httpClient.get("$BASE/$path") {
                parameter("apikey", apiKey)
                params()
            }.body()
            if (response.code != 200) {
                Timber.w("TheGamesDB returned code ${response.code} for $what")
                null
            } else {
                response
            }
        } catch (e: CancellationException) {
            // A cancelled browse is not "TheGamesDB has nothing" — see ArtworkStudioViewModel.loadResults.
            throw e
        } catch (e: Exception) {
            Timber.w(e, "TheGamesDB request failed for $what")
            null
        }
    }
}
