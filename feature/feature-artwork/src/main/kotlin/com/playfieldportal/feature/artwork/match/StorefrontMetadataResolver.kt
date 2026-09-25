package com.playfieldportal.feature.artwork.match

import com.playfieldportal.core.data.database.dao.GameStorefrontIdentityDao
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.database.entity.GameStorefrontIdentityEntity
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The storefront metadata resolver (C23 T6, Phase 17) — the one place the order of operations for
 * identifying a Windows game is written down.
 *
 * ```
 * stored identity ─┐
 * captured import ─┼─► an id ──► getMetadata ──► preset
 * 5-rule search ───┘      ▲
 *        └── score ── auto-link when EXACT/HIGH, else ask
 * ```
 *
 * **The rule the whole task exists for:** a title is a way to DISCOVER an id, never the
 * relationship itself. Once [GameStorefrontIdentityDao] holds a row for a game, this class does
 * not normalize its title, does not search, and does not score — it fetches by id and stops. A
 * title search runs again only when the id stops resolving, when the user unlinks it, or when the
 * user asks for a rematch.
 *
 * **Provider isolation (Phase 15).** Each provider is asked separately and its failure is recorded
 * against that provider alone. Steam being rate-limited says nothing about GOG, and no failure of
 * any kind is ever recorded as "this game does not exist".
 */
@Singleton
class StorefrontMetadataResolver @Inject constructor(
    private val providers: StorefrontProviders,
    private val identities: GameStorefrontIdentityDao,
    private val now: TimeSource = TimeSource.Default,
) {

    /** Injectable clock, so the identity rows a test writes have predictable timestamps. */
    fun interface TimeSource {
        fun millis(): Long

        companion object {
            val Default = TimeSource { java.lang.System.currentTimeMillis() }
        }
    }

    /** What resolving one game against one store produced. */
    sealed interface Resolution {

        /** An id was known or established, and the store answered with metadata. */
        data class Linked(
            val identity: StorefrontIdentityRecord,
            val preset: MetadataPreset,
            /** True when this run created the link rather than reusing a stored one. */
            val newlyLinked: Boolean,
        ) : Resolution

        /** Plausible candidates exist but the evidence does not settle it. The user decides. */
        data class NeedsConfirmation(val result: StorefrontMatchResult) : Resolution

        /** The store answered and does not have this game. Not an error; nothing is stored. */
        data object NoMatch : Resolution

        /** The store could not answer. Temporary by definition — see [StorefrontFailure]. */
        data class Unavailable(val store: Storefront, val failure: StorefrontOutcome.Failure) : Resolution
    }

    /** One game's outcome across every provider that was asked. */
    data class GameResolution(val gameId: Long, val byStore: Map<Storefront, Resolution>) {
        val presets: List<MetadataPreset>
            get() = byStore.values.filterIsInstance<Resolution.Linked>().map { it.preset }

        val needsConfirmation: Boolean
            get() = byStore.values.any { it is Resolution.NeedsConfirmation }

        val unavailableStores: List<Storefront>
            get() = byStore.values.filterIsInstance<Resolution.Unavailable>().map { it.store }
    }

    /**
     * Resolves [game] against every available provider.
     *
     * [allowAutoLink] is the escape hatch for a preview: an explicit Rematch screen wants to SHOW
     * what would be linked before anything is written, and passes false.
     *
     * [ignoreStoredIdentity] is Rematch's other half, and the ONLY way past the stored-identity
     * fast path. A user asking to rematch is saying the stored id is wrong, which is a thing only
     * they can know — so the door exists, it is never opened automatically, and the stored row
     * stays until they pick something else.
     */
    suspend fun resolve(
        game: GameEntity,
        allowAutoLink: Boolean = true,
        ignoreStoredIdentity: Boolean = false,
    ): GameResolution {
        val byStore = mutableMapOf<Storefront, Resolution>()
        for (provider in providers.all) {
            if (!provider.isAvailable()) continue
            byStore[provider.store] = runCatching { resolveOne(game, provider, allowAutoLink, ignoreStoredIdentity) }
                .onFailure { Timber.w(it, "Storefront resolve threw for %s", provider.store.key) }
                .getOrElse {
                    // A provider that throws is still only one provider down (Phase 15).
                    Resolution.Unavailable(
                        provider.store,
                        StorefrontOutcome.Failure(StorefrontFailure.PROVIDER_ERROR, it.message),
                    )
                }
        }
        return GameResolution(game.id, byStore)
    }

    private suspend fun resolveOne(
        game: GameEntity,
        provider: StorefrontMetadataProvider,
        allowAutoLink: Boolean,
        ignoreStoredIdentity: Boolean = false,
    ): Resolution {
        // 1 — a stored identity. The fast path, and the one that must never run a title search.
        storedIdentity(game.id, provider.store)?.takeIf { !ignoreStoredIdentity }?.let { stored ->
            return when (val metadata = provider.getMetadata(stored.storeId)) {
                is StorefrontOutcome.Ok -> {
                    identities.markVerified(game.id, provider.store.key, now.millis())
                    Resolution.Linked(stored, metadata.value, newlyLinked = false)
                }
                // The store no longer serves this id. That — and only that — re-opens discovery.
                StorefrontOutcome.NoMatch ->
                    if (stored.userConfirmed) {
                        // A link the user made themselves is theirs. A delisting is not licence to
                        // replace it behind their back; it is reported and left alone.
                        Resolution.NoMatch
                    } else {
                        discover(game, provider, allowAutoLink)
                    }
                is StorefrontOutcome.Failure -> Resolution.Unavailable(provider.store, metadata)
            }
        }

        // 2 — an authoritative id captured at import, for this same store. Also no title search.
        // Skipped on a forced rematch for the same reason as the stored row: the user is telling
        // PFP the id it has is wrong, and the captured pair is an id it has.
        authoritativeId(game, provider.store)?.takeIf { !ignoreStoredIdentity }?.let { appId ->
            return when (val metadata = provider.getMetadata(appId)) {
                is StorefrontOutcome.Ok -> {
                    val record = StorefrontIdentityRecord(
                        store = provider.store,
                        storeId = appId,
                        confidence = MatchConfidence.EXACT,
                        resolvedTitle = metadata.value.title,
                    )
                    if (allowAutoLink) persist(game.id, record)
                    Resolution.Linked(record, metadata.value, newlyLinked = true)
                }
                StorefrontOutcome.NoMatch -> discover(game, provider, allowAutoLink)
                is StorefrontOutcome.Failure -> Resolution.Unavailable(provider.store, metadata)
            }
        }

        // 3 — identity unknown. Only now does a title become involved.
        return discover(game, provider, allowAutoLink)
    }

    /** Phase 17 steps 3-8: normalize, search, score, and link only what is reliable enough. */
    private suspend fun discover(
        game: GameEntity,
        provider: StorefrontMetadataProvider,
        allowAutoLink: Boolean,
    ): Resolution {
        val query = StorefrontTitleNormalizer.normalize(displayTitleOf(game))
        if (query.searchCandidates.isEmpty()) return Resolution.NoMatch

        val candidates = when (val found = provider.search(query.searchCandidates)) {
            is StorefrontOutcome.Ok -> found.value
            StorefrontOutcome.NoMatch -> return Resolution.NoMatch
            is StorefrontOutcome.Failure -> return Resolution.Unavailable(provider.store, found)
        }

        val local = StorefrontMatchScorer.LocalEvidence(
            developer = game.developer,
            publisher = game.publisher,
            releaseYear = game.releaseYear,
        )
        val first = StorefrontMatchScorer.score(provider.store, query, candidates, local)
        // A search endpoint that returns names only leaves the scorer nothing but the title to go
        // on, which is exactly the situation that must not auto-link. Rather than give up, spend a
        // few detail requests on the top candidates and score again with real evidence.
        val result =
            if (first.confidence.autoLinkable || first.best == null) first
            else rescoreWithDetails(provider, query, first, local)

        // Nothing cleared the plausibility floor.
        //
        // For an automatic pass that is the final answer, and it has to be: a bulk run must not
        // queue a confirmation for every game a store merely has a near-namesake for. But when a
        // PERSON opened this screen (allowAutoLink = false is exactly that), hiding the near
        // misses is the wrong kind of caution — asking for `Bravely Default` and being told Steam
        // has nothing, while Steam plainly lists two games starting with those words, reads as a
        // broken search and leaves no way to say which one it is. The floor governs LINKING, not
        // looking; these arrive as LOW, which auto-links nothing.
        val best = result.best ?: return when {
            allowAutoLink -> Resolution.NoMatch
            result.alternatives.isEmpty() -> Resolution.NoMatch
            else -> Resolution.NeedsConfirmation(
                result.copy(
                    confidence = MatchConfidence.LOW,
                    best = result.alternatives.first(),
                    alternatives = result.alternatives.drop(1),
                )
            )
        }
        if (!result.confidence.autoLinkable) return Resolution.NeedsConfirmation(result)

        val preset = when (val metadata = provider.getMetadata(best.candidate.storeId)) {
            is StorefrontOutcome.Ok -> metadata.value
            StorefrontOutcome.NoMatch -> return Resolution.NoMatch
            is StorefrontOutcome.Failure -> return Resolution.Unavailable(provider.store, metadata)
        }

        val record = StorefrontIdentityRecord(
            store = provider.store,
            storeId = best.candidate.storeId,
            confidence = result.confidence,
            resolvedTitle = preset.title ?: best.candidate.title,
        )
        if (allowAutoLink) persist(game.id, record)
        return Resolution.Linked(record, preset, newlyLinked = true)
    }

    /**
     * Fills in developer, publisher and release year for the strongest few candidates and scores
     * them again.
     *
     * Capped at [DETAIL_ENRICHMENT_LIMIT] deliberately: the point is to separate the top of the
     * field, and a candidate that was already sixth on title is not going to win on a publisher.
     * Enrichment can only ever ADD evidence, so a field that was ambiguous stays ambiguous when
     * the store has nothing more to say — it never degrades a result.
     */
    private suspend fun rescoreWithDetails(
        provider: StorefrontMetadataProvider,
        query: StorefrontTitleNormalizer.NormalizedTitle,
        first: StorefrontMatchResult,
        local: StorefrontMatchScorer.LocalEvidence,
    ): StorefrontMatchResult {
        val top = (listOfNotNull(first.best) + first.alternatives).take(DETAIL_ENRICHMENT_LIMIT)
        if (top.size <= 1) return first

        val enriched = top.map { scored ->
            when (val details = provider.getMetadata(scored.candidate.storeId)) {
                is StorefrontOutcome.Ok -> scored.candidate.copy(
                    title = details.value.title ?: scored.candidate.title,
                    releaseYear = details.value.releaseYear ?: scored.candidate.releaseYear,
                    developer = details.value.developer ?: scored.candidate.developer,
                    publisher = details.value.publisher ?: scored.candidate.publisher,
                )
                // One candidate PFP could not enrich is not a reason to drop it from the field.
                else -> scored.candidate
            }
        }
        return StorefrontMatchScorer.score(provider.store, query, enriched, local)
    }

    // -- Identity storage ------------------------------------------------------

    /**
     * Stores a confirmed match — the entry point the ambiguous-match picker uses when the user
     * chooses a candidate, which is why [userConfirmed] defaults differently here.
     */
    suspend fun confirm(gameId: Long, candidate: StorefrontCandidate, confidence: MatchConfidence) {
        persist(
            gameId,
            StorefrontIdentityRecord(
                store = candidate.store,
                storeId = candidate.storeId,
                confidence = confidence,
                userConfirmed = true,
                resolvedTitle = candidate.title,
            ),
        )
    }

    /**
     * Removes one store's link. Nothing else moves: no metadata column, no user override and no
     * other store's identity is touched (Phase 18).
     */
    suspend fun unlink(gameId: Long, store: Storefront) = identities.delete(gameId, store.key)

    /** Every store this game is currently linked on — what a Rematch screen lists. */
    suspend fun linkedIdentities(gameId: Long): List<StorefrontIdentityRecord> =
        identities.forGame(gameId).mapNotNull { it.toRecord() }

    /**
     * The stores a provider actually exists for, in the order they are asked.
     *
     * The Rematch screen lists these and not [Storefront.entries]: a store with no provider cannot
     * be searched, and offering a dead Search button would be a lie about what PFP can do.
     */
    fun availableStores(): List<Storefront> = providers.all.map { it.store }

    private suspend fun storedIdentity(gameId: Long, store: Storefront): StorefrontIdentityRecord? =
        identities.get(gameId, store.key)?.toRecord()

    private suspend fun persist(gameId: Long, record: StorefrontIdentityRecord) {
        identities.upsert(
            GameStorefrontIdentityEntity(
                gameId = gameId,
                store = record.store.key,
                storeId = record.storeId,
                namespace = record.extras.namespace,
                catalogItemId = record.extras.catalogItemId,
                appName = record.extras.appName,
                confidence = record.confidence.name,
                userConfirmed = record.userConfirmed,
                resolvedTitle = record.resolvedTitle,
                linkedAt = now.millis(),
                lastVerifiedAt = now.millis(),
            )
        )
    }

    private fun GameStorefrontIdentityEntity.toRecord(): StorefrontIdentityRecord? {
        val resolvedStore = Storefront.fromKey(store) ?: return null
        if (storeId.isBlank()) return null
        return StorefrontIdentityRecord(
            store = resolvedStore,
            storeId = storeId,
            extras = StorefrontIdentityExtras(namespace, catalogItemId, appName),
            confidence = runCatching { MatchConfidence.valueOf(confidence) }
                .getOrDefault(MatchConfidence.HIGH),
            userConfirmed = userConfirmed,
            resolvedTitle = resolvedTitle,
        )
    }

    /**
     * The import-captured pair, but only when it names the store being asked. A GOG product id is
     * meaningless to Steam, and passing one in would be the exact cross-store mistake
     * `StorefrontIdentity` was written to prevent.
     */
    private fun authoritativeId(game: GameEntity, store: Storefront): String? {
        val captured = Storefront.fromKey(game.storefront) ?: return null
        if (captured != store) return null
        return game.storefrontGameId?.takeIf { it.isNotBlank() }
    }

    /** The same precedence the scrapers use: the user's title, then the scraped one, then the raw. */
    private fun displayTitleOf(game: GameEntity): String =
        game.userTitleOverride?.takeIf { it.isNotBlank() }
            ?: game.scrapedTitle?.takeIf { it.isNotBlank() }
            ?: game.title

    private companion object {
        const val DETAIL_ENRICHMENT_LIMIT = 3
    }
}

/**
 * The providers the resolver asks, in order.
 *
 * A wrapper rather than a `List<StorefrontMetadataProvider>` injected directly, so that adding GOG
 * is one line in `StorefrontModule` and no change anywhere else — and so the order is stated in
 * one place rather than being whatever the injector happened to produce.
 */
class StorefrontProviders(val all: List<StorefrontMetadataProvider>)
