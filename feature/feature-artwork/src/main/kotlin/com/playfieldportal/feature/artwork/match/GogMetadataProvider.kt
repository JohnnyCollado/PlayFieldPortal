package com.playfieldportal.feature.artwork.match

import com.playfieldportal.feature.artwork.api.GogCatalogProduct
import com.playfieldportal.feature.artwork.api.GogFailureKind
import com.playfieldportal.feature.artwork.api.GogGame
import com.playfieldportal.feature.artwork.api.GogResult
import com.playfieldportal.feature.artwork.api.GogStorefrontApi
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * GOG as a [StorefrontMetadataProvider] — the second implementation of the shared resolver
 * architecture, written after Steam proved it.
 *
 * Everything GOG-specific stops here: the two endpoints, the `pack`-is-a-game wrinkle, the two
 * date shapes, the HTML description and the two rating boards. Above this class a GOG match and a
 * Steam match are the same two types.
 *
 * Its queue and cache are its own, for the reason Steam's are: the budget being protected is
 * GOG's and nobody else's. Constructed by `StorefrontModule`, not by `@Inject`.
 */
class GogMetadataProvider(
    private val api: GogStorefrontApi,
    private val queue: StorefrontRequestQueue = StorefrontRequestQueue(),
    private val searchCache: StorefrontSearchCache = StorefrontSearchCache(),
) : StorefrontMetadataProvider {

    override val store: Storefront = Storefront.GOG

    /** No key, no account, no setting. */
    override suspend fun isAvailable(): Boolean = true

    /**
     * False until someone confirms it. The number a GameNative `.gog` export carries is that
     * launcher's `app_id`, and nothing establishes that it equals the gog.com product id — so a
     * GOG game is found by title, where a person or the scorer's evidence decides.
     */
    override val trustsCapturedId: Boolean = false

    override suspend fun search(titles: List<String>): StorefrontOutcome<List<StorefrontCandidate>> {
        val queries = titles.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (queries.isEmpty()) return StorefrontOutcome.NoMatch

        var lastFailure: StorefrontOutcome.Failure? = null
        for (query in queries) {
            val cached = searchCache.get(store, query)
            if (cached != null) {
                // A remembered EMPTY answer is still an answer: the broader query below is worth
                // trying, and this one is not worth asking again for a few hours.
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
        // A failure outranks an empty result: "GOG timed out" and "GOG does not have this game"
        // must never be recorded as the same thing.
        return lastFailure ?: StorefrontOutcome.NoMatch
    }

    private suspend fun runSearch(query: String): StorefrontOutcome<List<StorefrontCandidate>> =
        when (val result = api.search(query)) {
            is GogResult.Failure -> result.kind.toOutcome()
            is GogResult.Ok -> StorefrontOutcome.Ok(result.value.map(::candidateOf))
        }

    /**
     * A catalog hit as a candidate. Unlike Steam's search, GOG's returns companies and a date on
     * every row, so the scorer has corroboration without a detail request.
     */
    private fun candidateOf(product: GogCatalogProduct) = StorefrontCandidate(
        store = store,
        storeId = product.id,
        title = product.title,
        releaseYear = product.releaseDate?.let(::parseCatalogDate)?.year,
        developer = joined(product.developers),
        publisher = joined(product.publishers),
        thumbUrl = product.coverHorizontal?.takeIf { it.isNotBlank() },
    )

    override suspend fun getMetadata(storeId: String): StorefrontOutcome<MetadataPreset> {
        if (!isPlausibleProductId(storeId)) return StorefrontOutcome.NoMatch
        return when (val result = queue.submit("game:$storeId") { fetchGame(storeId) }) {
            is StorefrontOutcome.Failure -> result
            StorefrontOutcome.NoMatch -> StorefrontOutcome.NoMatch
            is StorefrontOutcome.Ok -> {
                val preset = presetOf(result.value)
                if (preset.isEmpty) StorefrontOutcome.NoMatch else StorefrontOutcome.Ok(preset)
            }
        }
    }

    /**
     * Whether GOG still serves this id. A [StorefrontOutcome.Failure] is returned as itself and
     * never as `false`: an id is only questioned because GOG SAID it is gone, not because the
     * network did.
     */
    override suspend fun validateIdentity(storeId: String): StorefrontOutcome<Boolean> {
        if (!isPlausibleProductId(storeId)) return StorefrontOutcome.Ok(false)
        return when (val result = queue.submit("game:$storeId") { fetchGame(storeId) }) {
            is StorefrontOutcome.Failure -> result
            StorefrontOutcome.NoMatch -> StorefrontOutcome.Ok(false)
            is StorefrontOutcome.Ok -> StorefrontOutcome.Ok(true)
        }
    }

    private suspend fun fetchGame(id: String): StorefrontOutcome<GogGame> =
        when (val result = api.game(id)) {
            is GogResult.Failure -> result.kind.toOutcome()
            is GogResult.Ok -> result.value?.let { StorefrontOutcome.Ok(it) } ?: StorefrontOutcome.NoMatch
        }

    /** GOG's fields as the shared metadata currency. Absent stays absent — never invented. */
    private fun presetOf(game: GogGame): MetadataPreset {
        val embedded = game.embedded
        val released = embedded?.product?.globalReleaseDate?.let(::parseGameDate)
        return MetadataPreset(
            provider = MatchProvider.GOG,
            title = embedded?.product?.title?.takeIf { it.isNotBlank() },
            description = gogPlainText(game.overview ?: game.description),
            developer = joined(embedded?.developers.orEmpty().mapNotNull { it.name }),
            publisher = embedded?.publisher?.name?.takeIf { it.isNotBlank() },
            // The game's date, never GOG's listing date: a 2016 game carrying the year GOG added
            // it would contradict a correct local year.
            releaseYear = released?.year,
            releaseDate = released?.format(DateTimeFormatter.ISO_LOCAL_DATE),
            genre = joined(embedded?.tags.orEmpty().mapNotNull { it.name }),
            // A board's own wording where there is one. ESRB first because its category is already
            // a label; PEGI is a bare age and is named so it is not read as a minimum-age gate.
            ageRating = embedded?.esrbRating?.category?.name?.takeIf { it.isNotBlank() }
                ?: embedded?.pegiRating?.ageRating?.takeIf { it > 0 }?.let { "PEGI $it" },
            // No franchise field exists on a GOG product. The catalog's reviewsRating is not
            // mapped: its scale was never established, and a rating on the wrong scale is worse
            // than none.
        )
    }

    private fun joined(names: List<String>): String? =
        names.map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(", ").takeIf { it.isNotBlank() }

    /** The catalog's `2016.05.13`. Anything else is no date rather than a guessed one. */
    private fun parseCatalogDate(raw: String): LocalDate? = try {
        LocalDate.parse(raw.trim(), CATALOG_DATE)
    } catch (_: DateTimeParseException) {
        null
    }

    /**
     * The game endpoint's `2016-08-30T00:00:00+02:00`. Only the calendar date is read: the time
     * and offset are GOG's midnight, and converting them would move some dates a day.
     */
    private fun parseGameDate(raw: String): LocalDate? = try {
        LocalDate.parse(raw.trim().take(ISO_DATE_LENGTH), DateTimeFormatter.ISO_LOCAL_DATE)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun isPlausibleProductId(id: String): Boolean =
        id.isNotBlank() && id.length <= MAX_PRODUCT_ID_LENGTH && id.all(Char::isDigit)

    private fun GogFailureKind.toOutcome(): StorefrontOutcome.Failure = StorefrontOutcome.Failure(
        when (this) {
            GogFailureKind.NETWORK_ERROR -> StorefrontFailure.NETWORK_ERROR
            GogFailureKind.RATE_LIMITED -> StorefrontFailure.RATE_LIMITED
            GogFailureKind.PROVIDER_ERROR -> StorefrontFailure.PROVIDER_ERROR
        }
    )

    private companion object {
        /** Product ids seen are ten digits; twelve leaves room without admitting a slug. */
        const val MAX_PRODUCT_ID_LENGTH = 12
        const val ISO_DATE_LENGTH = 10

        val CATALOG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")
    }
}

// -- HTML to text --------------------------------------------------------------

private val BLOCK_BREAK = Regex("""(?i)</p\s*>|</h[1-6]\s*>|</li\s*>|</div\s*>|<hr\s*/?>""")
private val LINE_BREAK = Regex("""(?i)<br\s*/?>""")
private val NON_TEXT_BLOCK = Regex("""(?is)<(script|style)\b[^>]*>.*?</\1\s*>""")
private val TAG = Regex("""<[^>]+>""")
private val NUMERIC_ENTITY = Regex("""&#(x?[0-9a-fA-F]+);""")
private val BLANK_RUN = Regex("""\n{3,}""")
private val SPACE_RUN = Regex("""[ \t ]+""")

private val NAMED_ENTITIES = mapOf(
    "&nbsp;" to " ", "&quot;" to "\"", "&apos;" to "'", "&lt;" to "<", "&gt;" to ">",
    "&ndash;" to "–", "&mdash;" to "—", "&hellip;" to "…", "&rsquo;" to "’", "&lsquo;" to "‘",
    "&rdquo;" to "”", "&ldquo;" to "“", "&copy;" to "©", "&reg;" to "®", "&trade;" to "™",
)

/**
 * GOG's store description as plain text, or null when nothing readable is left.
 *
 * GOG sends marketing HTML, and Game Detail renders text. Paragraphs are kept apart, markup and
 * link targets are dropped, and a description that was only banner images — The Witcher 3's is —
 * comes out null rather than as an empty string, so it is never offered as a value to apply.
 *
 * Deliberately small and regex-based: the input is one store's description field, not arbitrary
 * HTML, and the module has no HTML parser to reach for.
 */
internal fun gogPlainText(html: String?): String? {
    if (html.isNullOrBlank()) return null
    var text = NON_TEXT_BLOCK.replace(html, "")
    text = BLOCK_BREAK.replace(text, "\n\n")
    text = LINE_BREAK.replace(text, "\n")
    text = TAG.replace(text, "")
    NAMED_ENTITIES.forEach { (entity, char) -> text = text.replace(entity, char) }
    text = NUMERIC_ENTITY.replace(text) { match ->
        val code = match.groupValues[1]
        val value = if (code.startsWith("x", ignoreCase = true)) code.drop(1).toIntOrNull(16) else code.toIntOrNull()
        value?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: match.value
    }
    // Last, so an escaped `&amp;lt;` stays the literal text `&lt;` instead of becoming a tag.
    text = text.replace("&amp;", "&")
    text = text.lines().joinToString("\n") { SPACE_RUN.replace(it, " ").trim() }
    return BLANK_RUN.replace(text, "\n\n").trim().takeIf { it.isNotEmpty() }
}
