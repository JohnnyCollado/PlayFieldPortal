package com.playfieldportal.core.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The metadata fields a user can set by hand, as the keys of `games.user_metadata_overrides`.
 *
 * These are exactly the names of the artwork module's `MetadataField` entries. The enum itself
 * lives in `:feature:feature-artwork`, which core-domain must not depend on, so the contract is
 * restated here and pinned by a test on the artwork side — if a field is ever added or renamed
 * there, that test fails rather than an override silently becoming unreadable.
 */
object MetadataOverrideKeys {
    const val TITLE = "TITLE"
    const val DESCRIPTION = "DESCRIPTION"
    const val DEVELOPER = "DEVELOPER"
    const val PUBLISHER = "PUBLISHER"
    const val RELEASE_YEAR = "RELEASE_YEAR"
    const val RELEASE_DATE = "RELEASE_DATE"
    const val GENRE = "GENRE"
    const val AGE_RATING = "AGE_RATING"
    const val FRANCHISE = "FRANCHISE"
    const val COMMUNITY_RATING = "COMMUNITY_RATING"

    /** Every key, including [TITLE] — which is stored in its own column, never in the map. */
    val ALL: Set<String> = setOf(
        TITLE, DESCRIPTION, DEVELOPER, PUBLISHER, RELEASE_YEAR,
        RELEASE_DATE, GENRE, AGE_RATING, FRANCHISE, COMMUNITY_RATING,
    )

    /** The keys the JSON map may carry. [TITLE] is deliberately absent — see [MetadataOverrides]. */
    val IN_MAP: Set<String> = ALL - TITLE
}

/**
 * A game's hand-set metadata: the shadow layer that outranks every automatic writer.
 *
 * The scrapers keep refreshing the ordinary metadata columns as destructively as they like; they
 * simply never win on screen, because display coalesces an override over the stored value. Reverting
 * a field is removing its key, which reveals the scraped value underneath.
 *
 * TITLE is the one asymmetry, and it is deliberate: it stays in `games.user_title_override` because
 * four achievement queries read it **in SQL** (`COALESCE(g.user_title_override, g.title)`), which
 * SQLite cannot do against a key inside a JSON blob without rewriting each of them for no gain.
 * [of] folds the column back in, so callers see one uniform map and no call site has to know.
 *
 * A malformed blob reads as no overrides at all. A corrupt value must cost the user the overrides
 * on one game, never the ability to open its detail page.
 */
class MetadataOverrides private constructor(private val values: Map<String, JsonPrimitive>) {

    /** True when the user has set [key] by hand. */
    fun isOverridden(key: String): Boolean = key in values

    /** Every key the user has set, [MetadataOverrideKeys.TITLE] included when [of] built this. */
    val keys: Set<String> get() = values.keys

    val isEmpty: Boolean get() = values.isEmpty()

    /** The override on [key] as text; null when unset. Numbers render through their JSON form. */
    fun string(key: String): String? = values[key]?.content

    fun int(key: String): Int? = values[key]?.content?.toIntOrNull()

    fun float(key: String): Float? = values[key]?.content?.toFloatOrNull()

    /**
     * This map with [key] set to [value], or — when [value] is null or blank — with [key] removed.
     * Blank clearing the override is the same convention Edit Title already uses.
     */
    fun with(key: String, value: Any?): MetadataOverrides {
        val primitive = when (value) {
            null -> null
            is String -> value.trim().takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) }
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Float -> JsonPrimitive(value)
            is Double -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
        return MetadataOverrides(
            if (primitive == null) values - key else values + (key to primitive)
        )
    }

    /** This map without [key]. Revert-to-scraped is exactly this. */
    fun without(key: String): MetadataOverrides = MetadataOverrides(values - key)

    /**
     * The column value for this map: null once nothing is overridden, so a game the user has fully
     * reverted is indistinguishable from one that was never touched.
     */
    fun toColumnValue(): String? {
        val inMap = values.filterKeys { it in MetadataOverrideKeys.IN_MAP }
        if (inMap.isEmpty()) return null
        return JSON.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { inMap.forEach { (key, value) -> put(key, value) } },
        )
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        val EMPTY = MetadataOverrides(emptyMap())

        /**
         * Parses a `user_metadata_overrides` column value. Anything unparseable — bad JSON, a
         * top-level array, a nested object where a value belongs — reads as [EMPTY].
         */
        fun parse(json: String?): MetadataOverrides {
            if (json.isNullOrBlank()) return EMPTY
            val parsed = runCatching {
                JSON.parseToJsonElement(json) as? JsonObject
            }.getOrNull() ?: return EMPTY
            val values = buildMap {
                parsed.forEach { (key, element) ->
                    if (key !in MetadataOverrideKeys.IN_MAP) return@forEach
                    val primitive = runCatching { element.jsonPrimitive }.getOrNull() ?: return@forEach
                    if (primitive.content.isNotBlank()) put(key, primitive)
                }
            }
            return if (values.isEmpty()) EMPTY else MetadataOverrides(values)
        }

        /**
         * A row's complete overrides: the nine from the JSON map plus TITLE from its own column.
         * The one place that knows about the split — callers see a uniform ten-key map.
         *
         * Takes the two raw values rather than a type, so the data layer's `GameEntity` and the
         * domain's [Game] reach the same accessor without either module learning about the other.
         */
        fun of(userMetadataOverridesJson: String?, userTitleOverride: String?): MetadataOverrides {
            val map = parse(userMetadataOverridesJson)
            val title = userTitleOverride?.takeIf { it.isNotBlank() } ?: return map
            return map.with(MetadataOverrideKeys.TITLE, title)
        }

        fun of(game: Game): MetadataOverrides = of(game.userMetadataOverrides, game.userTitleOverride)
    }
}
