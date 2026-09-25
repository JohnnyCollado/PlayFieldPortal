package com.playfieldportal.feature.artwork.match

import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.domain.model.MetadataOverrides
import com.playfieldportal.feature.artwork.MetadataCandidates

/**
 * C16 task 3.2 — Current-vs-Incoming metadata, and the four ways to apply it.
 *
 * Pure: nothing here reads or writes the database. `ArtworkRepository.applyMetadata` is the only
 * writer and it asks [MetadataApply.plan] what to write, so the preview's "will change" markers and
 * the SQL that runs come from the same function and cannot disagree.
 */

/** One text field a [MetadataPreset] can carry, in preview order. `players` stays out (Non-Goals). */
enum class MetadataField(val label: String) {
    TITLE("Title"),
    DESCRIPTION("Description"),
    DEVELOPER("Developer"),
    PUBLISHER("Publisher"),
    RELEASE_YEAR("Year"),
    RELEASE_DATE("Release Date"),
    GENRE("Genre"),
    AGE_RATING("Age Rating"),
    FRANCHISE("Franchise"),
    COMMUNITY_RATING("Rating"),
}

enum class MetadataApplyPolicy(val label: String) {
    /** Every incoming value that differs overwrites the current one. A blank never clears. */
    REPLACE_ALL("Replace All"),

    /** Only fields empty today are filled — `GameDao.updateMetadataIfMissing`. */
    FILL_MISSING_ONLY("Fill Missing Only"),

    /** Replace All, restricted to the fields the user ticked. */
    CHOOSE_FIELDS("Choose Fields"),

    /** Close the preview and write nothing. */
    KEEP_CURRENT("Keep Current"),
}

/** One Current-vs-Incoming line. Only fields the provider actually supplied get a row. */
data class MetadataFieldRow(
    val field: MetadataField,
    val current: Any?,
    val incoming: Any,
) {
    val differs: Boolean get() = incoming != current
}

/**
 * What the preview shows: the game's values and every non-empty provider preset.
 *
 * Two "current" maps, because there are honestly two. [current] is what the metadata COLUMNS hold —
 * the only thing a provider preset can compare against or write, so it is what the Current column
 * shows beside one. [effective] is what the user SEES on Game Detail: their own value where they
 * set one, the scraped value otherwise. A manual edit is compared against that, which is also what
 * makes Fill Missing Only mean "neither an override nor a stored value" for a manual preset.
 */
data class MetadataPreview(
    val current: Map<MetadataField, Any?>,
    val effective: Map<MetadataField, Any?>,
    /**
     * The fields hand-set today. Read from the override map itself, not inferred by comparing
     * [effective] against [current]: a user who typed exactly what the scraper already stored has
     * still set that field, and must still be able to revert it.
     */
    val overridden: Set<MetadataField>,
    val presets: List<MetadataPreset>,
)

object MetadataApply {

    /**
     * Text presets from one retrieval. Only providers that return text can produce one:
     * ScreenScraper, TheGamesDB and — since C23 T5, which widened its Apicalypse field list from
     * cover/hero URLs to name, summary, involved companies, release date, genres and rating — IGDB.
     * SteamGridDB stays artwork-only by design and is never offered.
     *
     * A MANUAL preset is absent on purpose: it is built from what the user typed, not from what a
     * provider answered, so the ViewModel constructs it rather than this function.
     */
    fun presetsFrom(candidates: MetadataCandidates): List<MetadataPreset> = listOfNotNull(
        candidates.ssInfo?.let { ss ->
            MetadataPreset(
                provider = MatchProvider.SCREENSCRAPER,
                title = ss.title,
                description = ss.description,
                developer = ss.developer,
                publisher = ss.publisher,
                releaseYear = ss.releaseYear,
                releaseDate = ss.releaseDate,
                genre = ss.genre,
                ageRating = ss.ageRating,
                franchise = ss.franchise,
                communityRating = ss.communityRating,
            )
        },
        candidates.tgdbInfo?.let { tgdb ->
            MetadataPreset(
                provider = MatchProvider.THEGAMESDB,
                title = tgdb.title,
                description = tgdb.description,
                releaseYear = tgdb.releaseYear,
            )
        },
        candidates.igdbInfo?.let { igdb ->
            MetadataPreset(
                provider = MatchProvider.IGDB,
                title = igdb.title,
                description = igdb.description,
                developer = igdb.developer,
                publisher = igdb.publisher,
                releaseYear = igdb.releaseYear,
                releaseDate = igdb.releaseDate,
                genre = igdb.genre,
                // No age rating and no franchise: IGDB models both as separate joins this query
                // does not ask for, and an absent field is honest where an invented one is not.
                communityRating = igdb.communityRating,
            )
        },
    ).filterNot { it.isEmpty }

    /**
     * The stored values a preset is compared against. TITLE is `scraped_title`, never the user's
     * title override: a preset can refresh the scraped name, but the override always wins on screen
     * and nothing here can write it.
     */
    fun currentOf(game: GameEntity): Map<MetadataField, Any?> = mapOf(
        MetadataField.TITLE to game.scrapedTitle,
        MetadataField.DESCRIPTION to game.description,
        MetadataField.DEVELOPER to game.developer,
        MetadataField.PUBLISHER to game.publisher,
        MetadataField.RELEASE_YEAR to game.releaseYear,
        MetadataField.RELEASE_DATE to game.releaseDate,
        MetadataField.GENRE to game.genre,
        MetadataField.AGE_RATING to game.ageRating,
        MetadataField.FRANCHISE to game.franchise,
        MetadataField.COMMUNITY_RATING to game.communityRating,
    )

    /**
     * The values the user actually sees: their hand-set override where there is one, the stored
     * value otherwise. TITLE resolves the same way `Game.displayTitle` does, because
     * `MetadataOverrides.of` folds `user_title_override` back into the map.
     *
     * This is NOT what a provider preset is compared against — see [currentOf]. A provider can only
     * write the columns, so promising it a change against a value it cannot reach would be a lie.
     */
    fun overriddenFieldsOf(game: GameEntity): Set<MetadataField> {
        val overrides = MetadataOverrides.of(game.userMetadataOverrides, game.userTitleOverride)
        return MetadataField.entries.filterTo(mutableSetOf()) { overrides.isOverridden(it.name) }
    }

    fun effectiveOf(game: GameEntity): Map<MetadataField, Any?> {
        val overrides = MetadataOverrides.of(game.userMetadataOverrides, game.userTitleOverride)
        val stored = currentOf(game)
        return MetadataField.entries.associateWith { field ->
            when (field) {
                MetadataField.RELEASE_YEAR -> overrides.int(field.name)
                MetadataField.COMMUNITY_RATING -> overrides.float(field.name)
                else -> overrides.string(field.name)
            } ?: stored[field]
        }
    }

    /** The preset's usable values. Null and blank both mean "said nothing" and are dropped. */
    fun incomingOf(preset: MetadataPreset): Map<MetadataField, Any> = buildMap {
        fun offer(field: MetadataField, value: Any?) {
            if (value == null || (value is String && value.isBlank())) return
            put(field, value)
        }
        offer(MetadataField.TITLE, preset.title)
        offer(MetadataField.DESCRIPTION, preset.description)
        offer(MetadataField.DEVELOPER, preset.developer)
        offer(MetadataField.PUBLISHER, preset.publisher)
        offer(MetadataField.RELEASE_YEAR, preset.releaseYear)
        offer(MetadataField.RELEASE_DATE, preset.releaseDate)
        offer(MetadataField.GENRE, preset.genre)
        offer(MetadataField.AGE_RATING, preset.ageRating)
        offer(MetadataField.FRANCHISE, preset.franchise)
        offer(MetadataField.COMMUNITY_RATING, preset.communityRating)
    }

    fun rows(current: Map<MetadataField, Any?>, incoming: MetadataPreset): List<MetadataFieldRow> {
        val values = incomingOf(incoming)
        return MetadataField.entries.mapNotNull { field ->
            values[field]?.let { MetadataFieldRow(field, current[field], it) }
        }
    }

    /** Fields that would change — what Choose Fields starts with ticked. */
    fun changedFields(current: Map<MetadataField, Any?>, incoming: MetadataPreset): Set<MetadataField> =
        rows(current, incoming).filter { it.differs }.mapTo(mutableSetOf()) { it.field }

    /**
     * Exactly what [policy] writes: field → new value. Empty means no write at all.
     *
     * Fill Missing Only treats NULL as missing and nothing else, mirroring the reversed COALESCE it
     * runs through — a stored empty string is kept by the SQL, so the preview must not promise to
     * fill it.
     */
    fun plan(
        current: Map<MetadataField, Any?>,
        incoming: MetadataPreset,
        policy: MetadataApplyPolicy,
        chosen: Set<MetadataField>,
    ): Map<MetadataField, Any> {
        val changes = rows(current, incoming).filter { it.differs }
        return when (policy) {
            MetadataApplyPolicy.REPLACE_ALL -> changes
            MetadataApplyPolicy.FILL_MISSING_ONLY -> changes.filter { current[it.field] == null }
            MetadataApplyPolicy.CHOOSE_FIELDS -> changes.filter { it.field in chosen }
            MetadataApplyPolicy.KEEP_CURRENT -> emptyList()
        }.associate { it.field to it.incoming }
    }
}
