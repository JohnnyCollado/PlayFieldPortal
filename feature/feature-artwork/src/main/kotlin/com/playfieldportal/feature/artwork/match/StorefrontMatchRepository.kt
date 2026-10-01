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

        /**
         * Somebody has to choose. [query] is what the normalizer actually sent, and [pending] is
         * in the order the stores are asked.
         *
         * [unavailable] and [settled] are the stores that did NOT need a choice, carried because
         * one store having candidates must not speak for the others: a store that timed out is not
         * a store without the game, and a store already linked is not a store that was skipped.
         */
        data class NeedsChoice(
            val query: String,
            val pending: List<PendingMatch>,
            val unavailable: List<Storefront> = emptyList(),
            val settled: List<LinkedIdentity> = emptyList(),
        ) : Lookup

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
     *
     * [query] is a name the user typed into the Store Match search bar. The stores are asked for
     * that name instead of the game's title, the stored id is looked past, and the game itself is
     * not renamed. A blank one is no search at all.
     *
     * [store] limits the lookup to one store — what a Store Match row's own Search or Replace
     * passes, so that looking past that store's link leaves every other store's alone. Null asks
     * them all.
     */
    suspend fun lookup(
        gameId: Long,
        ignoreStoredIdentity: Boolean = false,
        query: String? = null,
        store: Storefront? = null,
    ): Lookup {
        val game = gameDao.getById(gameId) ?: return Lookup.Unknown
        if (game.platformId != WINDOWS_PLATFORM_ID) return Lookup.NotApplicable

        val typed = query?.trim()?.takeIf { it.isNotEmpty() }
        val resolution = runCatching {
            resolver.resolve(
                game,
                allowAutoLink = false,
                ignoreStoredIdentity = ignoreStoredIdentity || typed != null,
                titleOverride = typed,
                stores = store?.let(::setOf),
            )
        }.onFailure { Timber.w(it, "Storefront lookup failed for game %d", gameId) }
            .getOrNull() ?: return Lookup.Unknown

        // A stored link, as opposed to one this look merely found (which is pending — see pendingOf).
        val linked = resolution.byStore.values
            .filterIsInstance<StorefrontMetadataResolver.Resolution.Linked>()
            .filter { !it.newlyLinked }
            .map { LinkedIdentity(it.identity, it.identity.store.label) }
        val unavailable = resolution.unavailableStores

        // byStore keeps the order the providers were asked in, so `pending` does too.
        val pending = resolution.byStore.values.mapNotNull(::pendingOf)
        if (pending.isNotEmpty()) {
            val searchedAs = StorefrontTitleNormalizer.normalize(typed ?: displayTitleOf(game)).searchTitle
            return Lookup.NeedsChoice(searchedAs, pending, unavailable = unavailable, settled = linked)
        }

        if (linked.isNotEmpty()) return Lookup.Settled(linked)

        // A store being down outranks an empty answer, the same precedence the provider uses:
        // "Steam timed out" and "Steam does not have this game" lead to opposite actions.
        if (unavailable.isNotEmpty()) return Lookup.Unavailable(unavailable)

        return Lookup.NoMatch
    }

    /**
     * One store's answer as something to choose from, or null when there is nothing to ask.
     *
     * A link this run "made" is in here too. [lookup] never writes, so such a link exists only in
     * the resolver's answer: reporting it as settled told the user their game was matched while
     * the table still held the old id, or none. It is a finding, and it is shown as one.
     */
    private fun pendingOf(resolution: StorefrontMetadataResolver.Resolution): PendingMatch? = when (resolution) {
        is StorefrontMetadataResolver.Resolution.NeedsConfirmation -> resolution.result.toPending()
        is StorefrontMetadataResolver.Resolution.Linked -> when {
            !resolution.newlyLinked -> null
            resolution.match != null -> resolution.match.toPending()
            // An id PFP was handed rather than searched for: no field was scored, so the one
            // candidate is the game the store says that id is.
            else -> PendingMatch(
                store = resolution.identity.store,
                confidence = resolution.identity.confidence,
                candidates = listOf(
                    ScoredStorefrontCandidate(
                        StorefrontCandidate(
                            store = resolution.identity.store,
                            storeId = resolution.identity.storeId,
                            title = resolution.identity.resolvedTitle ?: resolution.identity.storeId,
                            releaseYear = resolution.preset.releaseYear,
                            developer = resolution.preset.developer,
                            publisher = resolution.preset.publisher,
                        ),
                        listOf(MatchSignal.AUTHORITATIVE_ID),
                    )
                ),
            )
        }
        else -> null
    }

    private fun StorefrontMatchResult.toPending(): PendingMatch? =
        (listOfNotNull(best) + alternatives).takeIf { it.isNotEmpty() }?.let { PendingMatch(store, confidence, it) }

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
        // A store with no provider (Epic) has nothing to offer here but a disabled Search, so it is
        // left off — unless the game is somehow linked there, when its row stays so the link can
        // still be removed.
        return Storefront.entries.filter { it in searchable || it in linked }.map { store ->
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
