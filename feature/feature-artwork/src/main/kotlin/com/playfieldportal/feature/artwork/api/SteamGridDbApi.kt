package com.playfieldportal.feature.artwork.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// ── Response models ───────────────────────────────────────────────────────────

@Serializable
data class SgdbSearchResponse(
    val success: Boolean,
    val data: List<SgdbGame> = emptyList(),
)

@Serializable
data class SgdbGame(
    val id: Long,
    val name: String,
    @SerialName("release_date") val releaseDate: Long? = null,
    val types: List<String> = emptyList(),
)

@Serializable
data class SgdbArtResponse(
    val success: Boolean,
    val data: List<SgdbArtItem> = emptyList(),
)

@Serializable
data class SgdbArtItem(
    val id: Long,
    val url: String,
    val thumb: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val style: String? = null,
)

// Art types the SteamGridDB API supports
enum class SgdbArtType(val endpoint: String) {
    GRID("grids"),      // 600×900 portrait grid — primary game card art
    HERO("heroes"),     // wide banner art — game details hero
    LOGO("logos"),      // transparent logo art
    ICON("icons"),      // square icon
}

// ── API client ────────────────────────────────────────────────────────────────

private const val BASE_URL = "https://www.steamgriddb.com/api/v2"

@Singleton
class SteamGridDbApi @Inject constructor(
    private val httpClient: HttpClient,
    private val apiKeyProvider: SgdbApiKeyProvider,
) {
    // Search for a game by name — returns best matches
    suspend fun searchGame(name: String): Result<List<SgdbGame>> = runCatching {
        val key = apiKeyProvider.getKey()
            ?: error("SteamGridDB API key not configured")

        val response: SgdbSearchResponse = httpClient.get("$BASE_URL/search/autocomplete/$name") {
            header("Authorization", "Bearer $key")
        }.body()

        if (!response.success) error("SteamGridDB search failed for: $name")
        response.data.also { Timber.d("SGDB search '$name' → ${it.size} results") }
    }

    // Fetch art of a given type for a known game ID
    suspend fun getArt(
        gameId: Long,
        type: SgdbArtType,
        styles: List<String> = emptyList(),
    ): Result<List<SgdbArtItem>> = runCatching {
        val key = apiKeyProvider.getKey()
            ?: error("SteamGridDB API key not configured")

        val response: SgdbArtResponse = httpClient.get("$BASE_URL/${type.endpoint}/game/$gameId") {
            header("Authorization", "Bearer $key")
            if (styles.isNotEmpty()) parameter("styles", styles.joinToString(","))
        }.body()

        if (!response.success) error("SGDB art fetch failed for gameId=$gameId type=$type")
        response.data.also { Timber.d("SGDB ${type.name} for $gameId → ${it.size} results") }
    }

    // Convenience — fetch best grid art URL for a game
    suspend fun getBestGridUrl(gameId: Long): String? =
        getArt(gameId, SgdbArtType.GRID)
            .getOrNull()
            ?.firstOrNull()
            ?.url

    suspend fun getBestHeroUrl(gameId: Long): String? =
        getArt(gameId, SgdbArtType.HERO)
            .getOrNull()
            ?.firstOrNull()
            ?.url

    suspend fun getBestLogoUrl(gameId: Long): String? =
        getArt(gameId, SgdbArtType.LOGO)
            .getOrNull()
            ?.firstOrNull()
            ?.url
}
