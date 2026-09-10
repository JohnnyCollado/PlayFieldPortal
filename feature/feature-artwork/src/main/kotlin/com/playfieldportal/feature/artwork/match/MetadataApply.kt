package com.playfieldportal.feature.artwork.match

import com.playfieldportal.core.data.database.entity.GameEntity
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

/** What the preview shows: the game's stored values and every non-empty provider preset. */
data class MetadataPreview(
    val current: Map<MetadataField, Any?>,
    val presets: List<MetadataPreset>,
)

object MetadataApply {

    /**
     * Text presets from one retrieval. Only providers that return text can produce one:
     * ScreenScraper and TheGamesDB. IGDB's `IgdbGameInfo` carries cover/hero URLs and no text today,
     * and SteamGridDB is artwork-only by design, so neither is offered.
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
