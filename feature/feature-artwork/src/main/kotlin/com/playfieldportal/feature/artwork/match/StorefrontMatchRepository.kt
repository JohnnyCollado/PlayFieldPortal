package com.playfieldportal.feature.artwork.match

import com.playfieldportal.core.data.database.dao.GameDao
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the storefront match picker and the Rematch screen talk to (C23 T6, Phases 10 and 18).
 *
 * The seam exists so that `feature-xmb` never touches `GameStorefrontIdentityDao` or a provider:
 * the UI asks a question in its own vocabulary — "what could this game be?", "the user picked
 * this one", "unlink Steam" — and this class is the only place that knows those answers come from
 * a resolver with a table behind it.
 *
 * Nothing here decides anything. [lookup] explicitly resolves with `allowAutoLink = false`,
 * because a screen the user opened must SHOW what it found before writing an identity; the only
 * write is [confirm], and it happens because a person chose.
 */
@Singleton
class StorefrontMatchRepository @Inject constructor(
    private val gameDao: GameDao,
    private val resolver: StorefrontMetadataResolver,
) {

    /** One store's unsettled answer — the rows the picker lists. */
    data class PendingMatch(
        val store: Storefront,
        val confidence: MatchConfidence,
        /** Ranked, best first. Always at least one, or there would be nothing to ask about. */
        val candidates: List<ScoredStorefrontCandidate>,
    )

    /** A store this game is linked to today, with the title it was linked under. */
    data class LinkedIdentity(
        val record: StorefrontIdentityRecord,
        val storeLabel: String,
    )

    /** What asking about one game produced. Exactly one of these, never a mixture. */
    sealed interface Lookup {

        /** Somebody has to choose. [query] is what the normalizer actually sent. */
        data class NeedsChoice(val query: String, val pending: List<PendingMatch>) : Lookup

        /** Every store that answered was already settled, or settled itself. Nothing to ask. */
        data class Settled(val identities: List<LinkedIdentity>) : Lookup

        /** The stores answered and none of them has this game. Not an error. */
        data object NoMatch : Lookup

        /** No store could be reached. Temporary — never recorded as "this game does not exist". */
        data class Unavailable(val stores: List<Storefront>) : Lookup

        /** Not a Windows game, so there is no storefront identity to look for. */
        data object NotApplicable : Lookup

        /** The row could not be read at all. */
        data object Unknown : Lookup
    }

    /**
     * Resolves [gameId] WITHOUT linking anything.
     *
     * [ignoreStoredIdentity] is Rematch's "search again": it is the only way past a stored id, it
     * still writes nothing, and the existing link survives until the user picks a replacement.
     */
    suspend fun lookup(gameId: Long, ignoreStoredIdentity: Boolean = false): Lookup {
        val game = gameDao.getById(gameId) ?: return Lookup.Unknown
        if (game.platformId != WINDOWS_PLATFORM_ID) return Lookup.NotApplicable

        val resolution = runCatching {
            resolver.resolve(game, allowAutoLink = false, ignoreStoredIdentity = ignoreStoredIdentity)
        }.onFailure { Timber.w(it, "Storefront lookup failed for game %d", gameId) }
            .getOrNull() ?: return Lookup.Unknown

        val pending = resolution.byStore.values
            .filterIsInstance<StorefrontMetadataResolver.Resolution.NeedsConfirmation>()
            .mapNotNull { needs ->
                val result = needs.result
                val candidates = listOfNotNull(result.best) + result.alternatives
                candidates.takeIf { it.isNotEmpty() }?.let {
                    PendingMatch(result.store, result.confidence, it)
                }
            }
        if (pending.isNotEmpty()) {
            val query = StorefrontTitleNormalizer.normalize(displayTitleOf(game)).searchTitle
            return Lookup.NeedsChoice(query, pending)
        }

        val linked = resolution.byStore.values
            .filterIsInstance<StorefrontMetadataResolver.Resolution.Linked>()
            .map { LinkedIdentity(it.identity, it.identity.store.label) }
        if (linked.isNotEmpty()) return Lookup.Settled(linked)

        // A store being down outranks an empty answer, the same precedence the provider uses:
        // "Steam timed out" and "Steam does not have this game" lead to opposite actions.
        val unavailable = resolution.unavailableStores
        if (unavailable.isNotEmpty()) return Lookup.Unavailable(unavailable)

        return Lookup.NoMatch
    }

    /** Every store this game is linked on today — what the Rematch screen lists. */
    suspend fun identities(gameId: Long): List<LinkedIdentity> =
        resolver.linkedIdentities(gameId).map { LinkedIdentity(it, it.store.label) }

    /** The user picked this one. The only write in this class. */
    suspend fun confirm(gameId: Long, candidate: StorefrontCandidate, confidence: MatchConfidence) {
        resolver.confirm(gameId, candidate, confidence)
        Timber.i("Storefront identity confirmed by user: game %d → %s:%s", gameId, candidate.store.key, candidate.storeId)
    }

    /**
     * Drops one store's link.
     *
     * Nothing else moves — no metadata column, no user override, no other store's identity. That
     * is the promise the Rematch screen makes on screen, and it is kept by this method doing
     * exactly one thing.
     */
    suspend fun unlink(gameId: Long, store: Storefront) {
        resolver.unlink(gameId, store)
        Timber.i("Storefront identity removed by user: game %d → %s", gameId, store.key)
    }

    /**
     * Every store, linked or not — one row per [Storefront], in enum order.
     *
     * All of them, not just the ones with providers: a user looking at this screen is asking "what
     * does PFP know about this game", and silently omitting GOG would answer "nothing to know"
     * where the truth is "not built yet". [RematchRow.searchable] carries that difference, so the
     * screen can say it rather than imply it.
     */
    suspend fun rematchRows(gameId: Long): List<RematchRow> {
        val linked = identities(gameId).associateBy { it.record.store }
        val searchable = resolver.availableStores().toSet()
        return Storefront.entries.map { store ->
            RematchRow(
                store = store,
                identity = linked[store],
                searchable = store in searchable,
            )
        }
    }

    /** One line of the Rematch screen. */
    data class RematchRow(
        val store: Storefront,
        /** Null when this game is not linked on this store. */
        val identity: LinkedIdentity?,
        /** False when no provider is built for this store yet — its Search is offered disabled. */
        val searchable: Boolean,
    )

    /** The same precedence the scrapers and the resolver use, over the row already in hand. */
    private fun displayTitleOf(game: com.playfieldportal.core.data.database.entity.GameEntity): String =
        game.userTitleOverride?.takeIf { it.isNotBlank() }
            ?: game.scrapedTitle?.takeIf { it.isNotBlank() }
            ?: game.title

    private companion object {
        const val WINDOWS_PLATFORM_ID = "windows"
    }
}
