package com.playfieldportal.feature.artwork.match

/**
 * C23 T6's storefront identity model and provider contract — pure data, no I/O, no persistence.
 *
 * The rule the whole task exists to enforce lives here in type form: a title search produces a
 * [StorefrontCandidate], scoring turns candidates into a [StorefrontMatchResult], and only a
 * result the policy accepts becomes a stored [StorefrontIdentityRecord]. From that point the id
 * is the relationship and no title is ever compared again.
 */

/**
 * A store PFP can resolve metadata from. [key] is exactly what `games.storefront` holds, so the
 * captured import identity and a resolved one are the same vocabulary.
 *
 * Only [STEAM] has a provider today. GOG and Epic are named because the identity model and the
 * table are shared and must not need a migration to gain them — the plan's Phase 22 is explicit
 * that the three are not built at once, so that the shared architecture is validated against one
 * store before a second one's quirks reach it.
 */
enum class Storefront(val key: String, val label: String) {
    STEAM("STEAM", "Steam"),
    GOG("GOG", "GOG"),
    EPIC("EPIC", "Epic Games Store");

    companion object {
        /** The store a stored `games.storefront` value names, or null for one PFP cannot resolve. */
        fun fromKey(key: String?): Storefront? =
            key?.trim()?.uppercase()?.let { k -> entries.firstOrNull { it.key == k } }
    }
}

/**
 * One game a storefront believes a local game could be, as returned by a search.
 *
 * [storeId] is the store's own primary key — a Steam appid, a GOG product id. It is only ever
 * compared within its own store: a Steam `620` and a GOG `620` are different games, the same rule
 * `StorefrontIdentity` already states for the captured import pair.
 *
 * Everything past [title] is a scoring signal and may be absent: a search endpoint that returns
 * only names produces candidates with nulls, and the scorer simply has less to go on, which is
 * what pushes such a match down to AMBIGUOUS rather than silently up to HIGH.
 */
data class StorefrontCandidate(
    val store: Storefront,
    val storeId: String,
    val title: String,
    val releaseYear: Int? = null,
    val developer: String? = null,
    val publisher: String? = null,
    val thumbUrl: String? = null,
)

/**
 * Epic's catalog needs three identifiers where Steam needs one, and GOG needs one of its own.
 * Modelled as optional companions to [StorefrontCandidate.storeId] rather than as three parallel
 * entities, because a single PFP game may hold an identity on every store at once (Phase 11) and
 * must never be split into three copies of itself.
 *
 * Steam fills none of these. They exist so the table that stores an identity does not need a
 * migration the day the Epic provider is written.
 */
data class StorefrontIdentityExtras(
    val namespace: String? = null,
    val catalogItemId: String? = null,
    val appName: String? = null,
)

/**
 * A storefront identity as it is held for one game — the permanent relationship a title search
 * exists to discover.
 *
 * [userConfirmed] outranks everything the resolver derives: a user who picked a match in the
 * picker has decided, and no later automatic pass may quietly replace it.
 */
data class StorefrontIdentityRecord(
    val store: Storefront,
    val storeId: String,
    val extras: StorefrontIdentityExtras = StorefrontIdentityExtras(),
    val confidence: MatchConfidence = MatchConfidence.EXACT,
    val userConfirmed: Boolean = false,
    val resolvedTitle: String? = null,
)

/**
 * How much evidence stands behind a storefront match (Phase 9).
 *
 * The ordering is the whole point: only [EXACT] and [HIGH] may link without asking, and a title
 * similarity on its own can never reach either. False positives are more harmful than a
 * confirmation prompt, so every borderline case falls to [AMBIGUOUS].
 */
enum class MatchConfidence {
    /** An authoritative store id, or one unique exact normalized-title hit. May auto-link. */
    EXACT,

    /** Several independent signals agree and nothing else comes close. May auto-link. */
    HIGH,

    /** More than one plausible game. Requires the user. Never auto-links. */
    AMBIGUOUS,

    /** A possible result with insufficient evidence. Never auto-links. */
    LOW,

    /** Nothing acceptable. The provider is left unlinked, which is not an error. */
    NO_MATCH;

    /** True for the two categories a resolver is allowed to store without asking anyone. */
    val autoLinkable: Boolean get() = this == EXACT || this == HIGH
}

/**
 * One deterministic reason a candidate scored what it did (Phase 8). Points are relative, not
 * absolute: what matters is that no single low-confidence signal can out-weigh a strong one.
 */
enum class MatchSignal(val points: Int, val strong: Boolean) {
    /** The store told PFP this id itself — a launcher intent, a library manifest. */
    AUTHORITATIVE_ID(100, strong = true),

    /** The candidate's normalized title equals the query's, character for character. */
    EXACT_TITLE(60, strong = true),

    /** Developer reported by the store matches the one already stored locally. */
    DEVELOPER(18, strong = true),

    /** Publisher reported by the store matches the one already stored locally. */
    PUBLISHER(18, strong = true),

    /** Release years agree — the signal that tells two same-named games apart. */
    RELEASE_YEAR(20, strong = true),

    /** Matched only after rule 4 removed an edition descriptor. Broadens, never establishes. */
    EDITION_STRIPPED_TITLE(25, strong = false),

    /** One normalized title contains the other. The weakest thing worth a point. */
    PARTIAL_TITLE(8, strong = false),

    /** Release years are both known and disagree. The only negative signal. */
    RELEASE_YEAR_CONFLICT(-40, strong = false),
}

/** A candidate with the reasons it was ranked where it was. */
data class ScoredStorefrontCandidate(
    val candidate: StorefrontCandidate,
    val signals: List<MatchSignal>,
) {
    val score: Int get() = signals.sumOf { it.points }

    /** True when at least one signal is something other than a title resemblance. */
    val hasCorroboration: Boolean
        get() = signals.any { it == MatchSignal.DEVELOPER || it == MatchSignal.PUBLISHER || it == MatchSignal.RELEASE_YEAR }
}

/**
 * What the scorer concluded for one store.
 *
 * [alternatives] is what the ambiguous-match picker would show. It is populated whenever there is
 * more than one plausible candidate, including when [confidence] is high enough to auto-link — the
 * user's Rematch flow needs the runners-up either way.
 */
data class StorefrontMatchResult(
    val store: Storefront,
    val confidence: MatchConfidence,
    val best: ScoredStorefrontCandidate?,
    val alternatives: List<ScoredStorefrontCandidate> = emptyList(),
)

// -- Provider contract --------------------------------------------------------

/**
 * Why a provider could not answer (Phase 15).
 *
 * The distinction this enum exists for: NONE of these is "the game does not exist". A timeout, a
 * rate limit and a 500 are all temporary, and treating one as NO_MATCH would burn the game's
 * chance of ever being matched and — worse — could write an empty result into a cache. NO_MATCH is
 * therefore not in this enum at all; it is an ordinary successful answer with nothing in it.
 */
enum class StorefrontFailure {
    NETWORK_ERROR,
    RATE_LIMITED,
    PROVIDER_ERROR,
    AUTH_REQUIRED;

    /** True when trying the same request later is reasonable. All of them, today. */
    val transient: Boolean get() = true
}

/**
 * A provider's answer: a value, an honest empty, or a named failure.
 *
 * Nothing above the providers catches exceptions from them — a provider converts its own
 * transport, status and parse problems into [Failure] so one store going down cannot end a bulk
 * run for the others (Phase 15).
 */
sealed interface StorefrontOutcome<out T> {

    data class Ok<T>(val value: T) : StorefrontOutcome<T>

    /** The provider answered, and it does not have this game. Not an error. */
    data object NoMatch : StorefrontOutcome<Nothing>

    data class Failure(
        val reason: StorefrontFailure,
        val message: String? = null,
    ) : StorefrontOutcome<Nothing>
}

/**
 * What every storefront looks like from outside (Phase 4). Nothing above this interface knows how
 * Steam searches, how Epic's catalog is addressed or what GOG's product ids look like — which is
 * the point, since those endpoints change on their own schedule and PFP's does not.
 *
 * All three operations are id-or-title symmetric on purpose: [search] is the only one that takes a
 * title, and it exists solely to find the id that the other two use forever after.
 */
interface StorefrontMetadataProvider {

    val store: Storefront

    /** True when this provider can be used at all — credentials, settings, availability. */
    suspend fun isAvailable(): Boolean = true

    /**
     * Candidates for [titles], which arrive already normalized and ordered strongest-first by
     * [StorefrontTitleNormalizer.NormalizedTitle.searchCandidates].
     *
     * The provider stops at the first query that returns anything: a second, broader query only
     * exists to rescue a first that found nothing, and running it anyway would spend a request to
     * dilute a result that was already good.
     */
    suspend fun search(titles: List<String>): StorefrontOutcome<List<StorefrontCandidate>>

    /** Full metadata for a known id — the call every game makes once its identity is stored. */
    suspend fun getMetadata(storeId: String): StorefrontOutcome<MetadataPreset>

    /**
     * Whether [storeId] still names a real game on this store. False is the one condition under
     * which a stored identity may be re-discovered by title (Phase 5); a [StorefrontOutcome.Failure]
     * must NOT be, or an outage would unlink a library.
     */
    suspend fun validateIdentity(storeId: String): StorefrontOutcome<Boolean>
}
