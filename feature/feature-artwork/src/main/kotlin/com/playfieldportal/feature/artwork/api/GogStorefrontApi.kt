package com.playfieldportal.feature.artwork.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

// -- Response models -----------------------------------------------------------
//
// Field paths are the ones seen in responses captured on 2026-09-30 (the fixtures under
// src/test/resources/gog/). Everything is optional: these endpoints are undocumented, so a field
// GOG stops sending must cost that field, not the whole response.

@Serializable
data class GogCatalogResponse(val products: List<GogCatalogProduct> = emptyList())

@Serializable
data class GogCatalogProduct(
    /** A JSON string in the catalog, a JSON number in the game endpoint — GOG's own inconsistency. */
    val id: String = "",
    val title: String = "",
    /** `game`, `pack` or `dlc` today. See [GogStorefrontApi.search] for which are kept. */
    val productType: String = "",
    /**
     * The game's own release date, `2016.05.13`. NOT `storeReleaseDate`, which sits beside it and
     * is the day GOG listed the product — DOOM (2016) carries 2025.04.18 there.
     */
    val releaseDate: String? = null,
    val developers: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    val coverHorizontal: String? = null,
)

@Serializable
data class GogGame(
    @SerialName("_embedded") val embedded: GogGameEmbedded? = null,
    /** HTML, and identical to [description] in every response seen. */
    val overview: String? = null,
    val description: String? = null,
)

@Serializable
data class GogGameEmbedded(
    val product: GogGameProduct? = null,
    val publisher: GogCompany? = null,
    val developers: List<GogCompany> = emptyList(),
    val tags: List<GogTag> = emptyList(),
    /** Present on some products and absent on others, not null. */
    val esrbRating: GogEsrbRating? = null,
    val pegiRating: GogPegiRating? = null,
)

@Serializable
data class GogGameProduct(
    val id: Long? = null,
    val title: String? = null,
    /** ISO with an offset, `2016-08-30T00:00:00+02:00`. The game's date; `gogReleaseDate` is GOG's. */
    val globalReleaseDate: String? = null,
)

@Serializable
data class GogCompany(val name: String? = null)

@Serializable
data class GogTag(val name: String? = null)

@Serializable
data class GogEsrbRating(val category: GogEsrbCategory? = null)

@Serializable
data class GogEsrbCategory(val name: String? = null)

@Serializable
data class GogPegiRating(val ageRating: Int? = null)

// -- Client --------------------------------------------------------------------

/**
 * GOG's public storefront endpoints: the catalog for discovery and the v2 game endpoint for
 * everything after it. Keyless, like Steam's.
 *
 * **Why these two.** `catalog.gog.com/v1/catalog` returns developers, publishers and a release
 * date on every hit, so the scorer has corroborating evidence without a detail request per
 * candidate. `api.gog.com/v2/games/{id}` carries every field a preset needs. The older
 * `api.gog.com/products/{id}?expand=description` returns no developer, publisher or genres, and
 * `embed.gog.com/games/ajax/filtered` returned nothing for any title when checked — neither is used.
 *
 * Every method converts its own transport, status and parse problems into a [GogResult], exactly
 * as [SteamStorefrontApi] does, so nothing above it has to catch.
 */
@Singleton
class GogStorefrontApi @Inject constructor(
    private val httpClient: HttpClient,
) {
    /** Products matching [term]. An empty list is a real answer; a failure means the request broke. */
    suspend fun search(term: String): GogResult<List<GogCatalogProduct>> =
        call("catalog '$term'", {
            httpClient.get("$CATALOG_BASE/v1/catalog") {
                header(USER_AGENT_HEADER, BROWSER_USER_AGENT)
                parameter("query", "like:$term")
                parameter("limit", SEARCH_LIMIT)
                parameter("order", "desc:score")
            }
        }) { response ->
            // Filtered here rather than with `productType=in:game`: a base game is often a `pack`
            // (The Witcher 3 and Cyberpunk 2077 both are), so the server-side game filter loses
            // the very product a user owns. DLC, and anything GOG invents later, is not a game a
            // library entry can be.
            response.body<GogCatalogResponse>().products
                .filter { it.id.isNotBlank() && it.productType.lowercase() in LINKABLE_TYPES }
        }

    /**
     * One product's details, or [GogResult.Ok] with null when GOG answers 404 — which is what an
     * unknown or delisted id returns, and is an answer rather than a failure.
     */
    suspend fun game(id: String): GogResult<GogGame?> =
        call<GogGame?>("game $id", onNotFound = { null }, request = {
            httpClient.get("$API_BASE/v2/games/$id") {
                header(USER_AGENT_HEADER, BROWSER_USER_AGENT)
            }
        }) { response -> response.body<GogGame>() }

    /**
     * Issues [request] and [parse]s a 2xx, turning every other ending into a named failure.
     *
     * [parse] is inside the same try on purpose: a shape change in GOG's JSON is a PROVIDER_ERROR —
     * a temporary condition to retry — and not "GOG has no such game". A cancelled call still
     * throws: a cancelled scan is not a GOG outage.
     *
     * [onNotFound] is set only where a 404 is an answer (an id GOG does not know). Without it a
     * 404 is a provider error like any other unexpected status: a search has no "unknown id".
     */
    private suspend fun <T> call(
        what: String,
        request: suspend () -> HttpResponse,
        onNotFound: (() -> T)? = null,
        parse: suspend (HttpResponse) -> T,
    ): GogResult<T> = try {
        val response = request()
        when {
            response.status.isSuccess() -> GogResult.Ok(parse(response))
            onNotFound != null && response.status == HttpStatusCode.NotFound -> GogResult.Ok(onNotFound())
            // GOG publishes no limit for these endpoints and none was provoked, so the shape of a
            // refusal is assumed to be the conventional one. Both are retryable either way.
            response.status == HttpStatusCode.TooManyRequests ||
                response.status == HttpStatusCode.Forbidden -> {
                Timber.w("GOG rate-limited (%s) for %s", response.status, what)
                GogResult.Failure(GogFailureKind.RATE_LIMITED)
            }
            else -> {
                Timber.w("GOG returned %s for %s", response.status, what)
                GogResult.Failure(GogFailureKind.PROVIDER_ERROR)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Timber.w(e, "GOG request failed for %s", what)
        GogResult.Failure(GogFailureKind.NETWORK_ERROR)
    } catch (e: Exception) {
        Timber.w(e, "GOG response unusable for %s", what)
        GogResult.Failure(GogFailureKind.PROVIDER_ERROR)
    }

    companion object {
        private const val CATALOG_BASE = "https://catalog.gog.com"
        private const val API_BASE = "https://api.gog.com"
        private const val SEARCH_LIMIT = 20
        private const val USER_AGENT_HEADER = "User-Agent"

        /** Whether GOG requires one was not established; sent for the reason Steam's is. */
        private const val BROWSER_USER_AGENT = "Mozilla/5.0 (compatible; PlayFieldPortal)"

        private val LINKABLE_TYPES = setOf("game", "pack")
    }
}

/** Why a GOG call did not produce data. Mapped to the shared failure taxonomy by the provider. */
enum class GogFailureKind { NETWORK_ERROR, RATE_LIMITED, PROVIDER_ERROR }

/** A GOG call's answer, before it is translated into the store-agnostic outcome type. */
sealed interface GogResult<out T> {
    data class Ok<T>(val value: T) : GogResult<T>
    data class Failure(val kind: GogFailureKind) : GogResult<Nothing>
}
