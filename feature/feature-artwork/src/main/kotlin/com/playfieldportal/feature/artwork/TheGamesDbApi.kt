package com.playfieldportal.feature.artwork

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

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
    }

    suspend fun fetchGameInfo(
        platformId: String,
        title: String,
    ): TgdbGameInfo? {
        val apiKey = keyProvider.getTgdbKey() ?: run {
            Timber.d("TheGamesDB: no API key configured")
            return null
        }
        val tgdbPlatformId = PLATFORM_IDS[platformId]

        return try {
            val response: TgdbGamesResponse = httpClient.get("$BASE/Games/ByGameName") {
                parameter("apikey", apiKey)
                parameter("name", title)
                parameter("fields", "overview,release_date,rating")
                parameter("include", "boxart")
                if (tgdbPlatformId != null) parameter("filter[platform]", tgdbPlatformId)
            }.body()

            if (response.code != 200) {
                Timber.w("TheGamesDB returned code ${response.code} for '$title'")
                return null
            }

            val game = response.data?.games?.firstOrNull() ?: return null
            val baseUrl = response.include?.boxart?.baseUrl?.large
                ?: response.include?.boxart?.baseUrl?.original
                ?: ""
            val images = response.include?.boxart?.data?.get(game.id.toString()) ?: emptyList()

            val artworkUrl = images.firstOrNull { it.type == "boxart" && it.side == "front" }
                ?.filename?.let { "$baseUrl$it" }
            val heroUrl = images.firstOrNull { it.type == "fanart" }
                ?.filename?.let { "$baseUrl$it" }
            val logoUrl = images.firstOrNull { it.type == "clearlogo" }
                ?.filename?.let { "$baseUrl$it" }

            TgdbGameInfo(
                tgdbId      = game.id,
                title       = game.gameTitle,
                description = game.overview,
                releaseYear = game.releaseDate?.take(4)?.toIntOrNull(),
                artworkUrl  = artworkUrl,
                heroUrl     = heroUrl,
                logoUrl     = logoUrl,
            )
        } catch (e: Exception) {
            Timber.w(e, "TheGamesDB fetch failed for '$title'")
            null
        }
    }
}
