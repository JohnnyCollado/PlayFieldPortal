package com.playfieldportal.feature.artwork.match

import com.playfieldportal.feature.artwork.api.SteamAppDetailsData
import com.playfieldportal.feature.artwork.api.SteamFailureKind
import com.playfieldportal.feature.artwork.api.SteamResult
import com.playfieldportal.feature.artwork.api.SteamStorefrontApi
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.LocalDate
import java.util.Locale

/**
 * Steam as a [StorefrontMetadataProvider] (C23 T6, Phase 5) — the first implementation of the
 * shared resolver architecture, and the one it is validated against before GOG and Epic are
 * written (Phase 22).
 *
 * Everything Steam-specific stops here: the endpoint shapes, the `app`-vs-`bundle` distinction,
 * the locale-formatted release date and the metacritic scale. Above this class a Steam match and a
 * GOG match are the same two types.
 *
 * **Requests are queued, deduplicated and cached at this level, not above it**, because the budget
 * being protected is Steam's and nobody else's: an Epic outage must not slow Steam down, and a
 * Steam rate limit must not throttle GOG. Fifty library rows that normalize to one title cost one
 * search.
 *
 * Constructed by `StorefrontModule` rather than by `@Inject`, because the queue and the cache are
 * per-provider collaborators with tuned defaults — an injected one would be shared with whatever
 * provider is written next, which is exactly the coupling Phase 15 exists to prevent.
 */
class SteamMetadataProvider(
    private val api: SteamStorefrontApi,
    private val queue: StorefrontRequestQueue = StorefrontRequestQueue(),
    private val searchCache: StorefrontSearchCache = StorefrontSearchCache(),
) : StorefrontMetadataProvider {

    override val store: Storefront = Storefront.STEAM

    /** No key, no account, no setting — which is the whole reason this provider exists. */
    override suspend fun isAvailable(): Boolean = true

    override suspend fun search(titles: List<String>): StorefrontOutcome<List<StorefrontCandidate>> {
        val queries = titles.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (queries.isEmpty()) return StorefrontOutcome.NoMatch

        var lastFailure: StorefrontOutcome.Failure? = null
        for (query in queries) {
            val cached = searchCache.get(store, query)
            if (cached != null) {
                // A remembered EMPTY answer is still an answer: it means the broader query below is
                // worth trying, and that this query is not worth asking again for a few hours.
                if (cached.isNotEmpty()) return StorefrontOutcome.Ok(cached)
                continue
            }
            when (val result = queue.submit("search:$query") { runSearch(query) }) {
                is StorefrontOutcome.Ok -> {
                    searchCache.put(store, query, result.value)
                    if (result.value.isNotEmpty()) return result
                }
                is StorefrontOutcome.Failure -> lastFailure = result
                StorefrontOutcome.NoMatch -> Unit
            }
        }
        // A failure outranks an empty result: "Steam timed out" and "Steam does not have this
        // game" must never be recorded as the same thing (Phase 15).
        return lastFailure ?: StorefrontOutcome.NoMatch
    }

    private suspend fun runSearch(query: String): StorefrontOutcome<List<StorefrontCandidate>> =
        when (val result = api.search(query)) {
            is SteamResult.Failure -> result.kind.toOutcome()
            is SteamResult.Ok -> StorefrontOutcome.Ok(
                result.value.map { item ->
                    StorefrontCandidate(
                        store = store,
                        storeId = item.id.toString(),
                        title = item.name,
                        // storesearch returns no company or date fields at all. The resolver fills
                        // them from appdetails for the few candidates it actually needs to tell
                        // apart, rather than spending a request per hit here.
                        thumbUrl = item.tinyImage,
                    )
                }
            )
        }

    override suspend fun getMetadata(storeId: String): StorefrontOutcome<MetadataPreset> {
        if (!isPlausibleAppId(storeId)) return StorefrontOutcome.NoMatch
        return when (val result = queue.submit("details:$storeId") { fetchDetails(storeId) }) {
            is StorefrontOutcome.Failure -> result
            StorefrontOutcome.NoMatch -> StorefrontOutcome.NoMatch
            is StorefrontOutcome.Ok -> {
                val preset = presetOf(result.value)
                if (preset.isEmpty) StorefrontOutcome.NoMatch else StorefrontOutcome.Ok(preset)
            }
        }
    }

    /**
     * Whether Steam still serves this appid. A [StorefrontOutcome.Failure] is returned as itself
     * and never as `false`: an id must only ever be questioned because Steam SAID it is gone, not
     * because the network did.
     */
    override suspend fun validateIdentity(storeId: String): StorefrontOutcome<Boolean> {
        if (!isPlausibleAppId(storeId)) return StorefrontOutcome.Ok(false)
        return when (val result = queue.submit("details:$storeId") { fetchDetails(storeId) }) {
            is StorefrontOutcome.Failure -> result
            StorefrontOutcome.NoMatch -> StorefrontOutcome.Ok(false)
            is StorefrontOutcome.Ok -> StorefrontOutcome.Ok(true)
        }
    }

    private suspend fun fetchDetails(appId: String): StorefrontOutcome<SteamAppDetailsData> =
        when (val result = api.appDetails(appId)) {
            is SteamResult.Failure -> result.kind.toOutcome()
            is SteamResult.Ok -> result.value?.let { StorefrontOutcome.Ok(it) } ?: StorefrontOutcome.NoMatch
        }

    /** Steam's fields as the shared metadata currency. Absent stays absent — never invented. */
    private fun presetOf(data: SteamAppDetailsData): MetadataPreset {
        val releaseDate = data.releaseDate?.takeIf { !it.comingSoon }?.date
        val parsed = releaseDate?.let(::parseReleaseDate)
        return MetadataPreset(
            provider = MatchProvider.STEAM,
            title = data.name?.takeIf { it.isNotBlank() },
            // The short description, not the detailed one: the detailed body is marketing HTML
            // with images and headings in it, and Game Detail renders plain text.
            description = data.shortDescription?.takeIf { it.isNotBlank() },
            developer = data.developers.filter { it.isNotBlank() }.distinct()
                .joinToString(", ").takeIf { it.isNotBlank() },
            publisher = data.publishers.filter { it.isNotBlank() }.distinct()
                .joinToString(", ").takeIf { it.isNotBlank() },
            releaseYear = parsed?.year ?: releaseDate?.let { YEAR.find(it)?.value?.toIntOrNull() },
            // Only an exactly-parsed date becomes a date. `Q3 2026` and `Coming soon` yield a year
            // at most, because a half-known date written as an ISO one would be a fabrication.
            releaseDate = parsed?.format(DateTimeFormatter.ISO_LOCAL_DATE),
            genre = data.genres.mapNotNull { it.description?.takeIf(String::isNotBlank) }
                .distinct().joinToString(", ").takeIf { it.isNotBlank() },
            // Steam's `required_age` is a minimum age, not a board rating, so it is reported as
            // one. Zero means "no gate", which is not a rating and is dropped.
            ageRating = data.requiredAge?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { "$it+" },
            // Metacritic is 0..100; community ratings are stored 0..1, the same scale IGDB's
            // total_rating and ScreenScraper's /20 note are normalized to.
            communityRating = data.metacritic?.score
                ?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) },
        )
    }

    /**
     * Steam's release date in the two shapes the `l=en` storefront actually returns. Anything else
     * — a quarter, a year alone, a localized month — parses to null and leaves the year regex to
     * salvage what it can.
     */
    private fun parseReleaseDate(raw: String): LocalDate? {
        val text = raw.trim()
        for (format in DATE_FORMATS) {
            try {
                return LocalDate.parse(text, format)
            } catch (_: DateTimeParseException) {
                // Next shape.
            }
        }
        return null
    }

    private fun isPlausibleAppId(id: String): Boolean =
        id.isNotBlank() && id.length <= MAX_APP_ID_LENGTH && id.all(Char::isDigit)

    private fun SteamFailureKind.toOutcome(): StorefrontOutcome.Failure = StorefrontOutcome.Failure(
        when (this) {
            SteamFailureKind.NETWORK_ERROR -> StorefrontFailure.NETWORK_ERROR
            SteamFailureKind.RATE_LIMITED -> StorefrontFailure.RATE_LIMITED
            SteamFailureKind.PROVIDER_ERROR -> StorefrontFailure.PROVIDER_ERROR
        }
    )

    private companion object {
        const val MAX_APP_ID_LENGTH = 12

        val YEAR = Regex("""(?:19|20)\d{2}""")

        /** `9 Jul, 2013` and `Jul 9, 2013` — the two the English storefront emits. */
        val DATE_FORMATS: List<DateTimeFormatter> = listOf(
            DateTimeFormatter.ofPattern("d MMM, yyyy", Locale.US),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US),
        )
    }
}
