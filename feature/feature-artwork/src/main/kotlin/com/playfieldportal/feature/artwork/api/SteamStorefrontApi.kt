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

@Serializable
data class SteamSearchResponse(
    val total: Int = 0,
    val items: List<SteamSearchItem> = emptyList(),
)

@Serializable
data class SteamSearchItem(
    val id: Long = 0,
    val name: String = "",
    /** `app`, `bundle`, `sub`, `dlc`. Only `app` is a game PFP can address by appid. */
    val type: String = "",
    @SerialName("tiny_image") val tinyImage: String? = null,
)

@Serializable
data class SteamAppDetailsEntry(
    val success: Boolean = false,
    val data: SteamAppDetailsData? = null,
)

@Serializable
data class SteamAppDetailsData(
    val type: String? = null,
    val name: String? = null,
    @SerialName("steam_appid") val steamAppId: Long? = null,
    // Steam returns this as a bare number on some apps and a quoted string on others. Declared as
    // a String and read with the client's lenient Json, so neither shape throws.
    @SerialName("required_age") val requiredAge: String? = null,
    @SerialName("short_description") val shortDescription: String? = null,
    val developers: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    val genres: List<SteamGenre> = emptyList(),
    @SerialName("release_date") val releaseDate: SteamReleaseDate? = null,
    val metacritic: SteamMetacritic? = null,
    @SerialName("header_image") val headerImage: String? = null,
)

@Serializable
data class SteamGenre(val id: String? = null, val description: String? = null)

@Serializable
data class SteamReleaseDate(
    @SerialName("coming_soon") val comingSoon: Boolean = false,
    /** Locale-formatted and unparseable in general — `9 Jul, 2013`, `Jul 9, 2013`, `Q3 2026`. */
    val date: String? = null,
)

@Serializable
data class SteamMetacritic(val score: Int? = null, val url: String? = null)

// -- Client --------------------------------------------------------------------

/**
 * Steam's public storefront endpoints (C23 T6, Phase 5) — `storesearch` for discovery and
 * `appdetails` for everything after it. Keyless, which is the entire reason T6 exists: a user who
 * has never configured IGDB/Twitch credentials still gets Windows metadata.
 *
 * **Valve's own endpoints, not a third party.** SteamDB is useful for reading Steam's data by hand
 * and is not PFP's backend: everything here comes from `store.steampowered.com`. The deprecated
 * `ISteamApps/GetAppList` is also not used — it hands back a hundred-thousand-row app list to
 * answer one question, and the storefront search answers that question directly.
 *
 * **Why this duplicates `feature-achievements`' `SteamStoreApi` rather than importing it.** Three
 * reasons, in order of weight: a feature module must not depend on another feature module; that
 * client is Retrofit/OkHttp where this module is Ktor throughout; and it requests
 * `filters=basic`, which returns the name and nothing else — no developer, publisher, release date
 * or genres, which is precisely the payload a metadata provider exists to fetch. What is shared is
 * the knowledge that these endpoints exist and want a browser User-Agent.
 *
 * Every method converts its own transport, status and parse problems into a
 * [com.playfieldportal.feature.artwork.match.StorefrontOutcome], so nothing above it has to catch.
 */
@Singleton
class SteamStorefrontApi @Inject constructor(
    private val httpClient: HttpClient,
) {
    /** Games matching [term]. An empty list is a real answer; a failure means the request broke. */
    suspend fun search(term: String, countryCode: String = DEFAULT_CC): SteamResult<List<SteamSearchItem>> =
        call("storesearch '$term'", {
            httpClient.get("$BASE/api/storesearch/") {
                header(USER_AGENT_HEADER, BROWSER_USER_AGENT)
                parameter("term", term)
                parameter("cc", countryCode)
                parameter("l", DEFAULT_LANGUAGE)
            }
        }) { response ->
            // `bundle` and `sub` ids are not appids and would poison an identity; only real apps
            // survive the filter.
            response.body<SteamSearchResponse>().items
                .filter { it.type.equals("app", ignoreCase = true) && it.id > 0 }
        }

    /**
     * Full store details for one appid, or [SteamResult.Ok] with null when Steam answers
     * `success: false` — which is what a delisted, region-locked or simply wrong appid returns,
     * and is an answer rather than a failure.
     */
    suspend fun appDetails(appId: String, countryCode: String = DEFAULT_CC): SteamResult<SteamAppDetailsData?> =
        call("appdetails $appId", {
            httpClient.get("$BASE/api/appdetails/") {
                header(USER_AGENT_HEADER, BROWSER_USER_AGENT)
                parameter("appids", appId)
                parameter("l", DEFAULT_LANGUAGE)
                parameter("cc", countryCode)
            }
        }) { response ->
            val body = response.body<Map<String, SteamAppDetailsEntry>>()
            val entry = body[appId] ?: body.values.firstOrNull()
            entry?.takeIf { it.success }?.data
        }

    /**
     * Issues [request] and [parse]s a 2xx, turning every other ending into a named failure.
     *
     * [parse] is inside the same try on purpose: a shape change in Steam's JSON is a PROVIDER_ERROR
     * — a temporary condition to retry — and not "Steam has no such game", which is what letting a
     * deserialization exception escape into an empty result would eventually be recorded as.
     * A cancelled call still throws: a cancelled scan is not a Steam outage.
     */
    private suspend fun <T> call(
        what: String,
        request: suspend () -> HttpResponse,
        parse: suspend (HttpResponse) -> T,
    ): SteamResult<T> = try {
        val response = request()
        when {
            response.status.isSuccess() -> SteamResult.Ok(parse(response))
            // 429 is the documented shape of Steam's soft limit; a 403 from the storefront is the
            // same thing wearing a different hat (it is what a burst of requests earns).
            response.status == HttpStatusCode.TooManyRequests ||
                response.status == HttpStatusCode.Forbidden -> {
                Timber.w("Steam rate-limited (%s) for %s", response.status, what)
                SteamResult.Failure(SteamFailureKind.RATE_LIMITED)
            }
            else -> {
                Timber.w("Steam returned %s for %s", response.status, what)
                SteamResult.Failure(SteamFailureKind.PROVIDER_ERROR)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Timber.w(e, "Steam request failed for %s", what)
        SteamResult.Failure(SteamFailureKind.NETWORK_ERROR)
    } catch (e: Exception) {
        Timber.w(e, "Steam response unusable for %s", what)
        SteamResult.Failure(SteamFailureKind.PROVIDER_ERROR)
    }

    companion object {
        private const val BASE = "https://store.steampowered.com"
        private const val DEFAULT_CC = "us"
        private const val DEFAULT_LANGUAGE = "en"
        private const val USER_AGENT_HEADER = "User-Agent"

        /**
         * The storefront rejects default HTTP-library agents. Nothing sensitive travels in these
         * requests — they are keyless and carry no account identifier — so this is politeness
         * about being served, not concealment.
         */
        private const val BROWSER_USER_AGENT = "Mozilla/5.0 (compatible; PlayFieldPortal)"
    }
}

/** Why a Steam call did not produce data. Mapped to the shared failure taxonomy by the provider. */
enum class SteamFailureKind { NETWORK_ERROR, RATE_LIMITED, PROVIDER_ERROR }

/** A Steam call's answer, before it is translated into the store-agnostic outcome type. */
sealed interface SteamResult<out T> {
    data class Ok<T>(val value: T) : SteamResult<T>
    data class Failure(val kind: SteamFailureKind) : SteamResult<Nothing>
}
