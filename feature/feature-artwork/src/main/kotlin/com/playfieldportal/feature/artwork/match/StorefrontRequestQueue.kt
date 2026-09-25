package com.playfieldportal.feature.artwork.match

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One storefront's request discipline (C23 T6, Phase 16): spacing, in-flight deduplication and a
 * bounded retry, per provider and never shared between them.
 *
 * **Why this is not a semaphore.** A Windows library is scanned in one pass, and the thing that
 * gets a keyless endpoint to start refusing is not concurrency, it is volume of DISTINCT requests.
 * Fifty shortcuts that normalize to `the witcher 3 wild hunt` are one question asked fifty times;
 * answering it once and handing the same answer to the other forty-nine is worth more than any
 * amount of parallelism tuning. So the queue is serial by construction and deduplicates by key.
 *
 * **Why the deduplication is by CompletableDeferred and not a cache.** A cache answers a repeat
 * that arrives after the first one finished. These arrive while it is still in flight, which a
 * cache cannot help with — the second caller has to wait for the first caller's request, not start
 * its own. [StorefrontSearchCache] handles the after case; this handles the during case, and a
 * provider wants both.
 *
 * Failures are NOT shared: a [StorefrontOutcome.Failure] is returned to every waiter but is never
 * remembered, so the next scan of the same game asks again rather than inheriting an outage.
 */
class StorefrontRequestQueue(
    /** Minimum gap between two requests leaving this queue. */
    private val minIntervalMs: Long = DEFAULT_INTERVAL_MS,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val gate = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<StorefrontOutcome<Any?>>>()
    // Null until the first request leaves, so the very first one is not made to wait out a gap
    // since a request that never happened.
    private var lastStartedAt: Long? = null

    /**
     * Runs [request] under this queue's discipline, or joins the identical request already running.
     *
     * [key] must identify the REQUEST, not the caller: two games resolving the same normalized
     * title share a key and therefore share one network round trip.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> submit(
        key: String,
        request: suspend () -> StorefrontOutcome<T>,
    ): StorefrontOutcome<T> {
        val existing = gate.withLock { inFlight[key] }
        if (existing != null) return existing.await() as StorefrontOutcome<T>

        val mine = CompletableDeferred<StorefrontOutcome<Any?>>()
        val owner = gate.withLock {
            // Re-checked under the lock: two coroutines can both read null above.
            val racedTo = inFlight[key]
            if (racedTo != null) return@withLock racedTo
            inFlight[key] = mine
            mine
        }
        if (owner !== mine) return owner.await() as StorefrontOutcome<T>

        return try {
            waitForSlot()
            val outcome = request()
            mine.complete(outcome as StorefrontOutcome<Any?>)
            outcome
        } catch (e: Throwable) {
            // A thrown provider is a bug, not an outcome — but the waiters must still be released,
            // or every other game sharing this key hangs for the rest of the run.
            mine.complete(StorefrontOutcome.Failure(StorefrontFailure.PROVIDER_ERROR, e.message))
            throw e
        } finally {
            gate.withLock { inFlight.remove(key) }
        }
    }

    /** Holds the caller until [minIntervalMs] has passed since the last request left. */
    private suspend fun waitForSlot() {
        val wait = gate.withLock {
            val previous = lastStartedAt
            val remaining = if (previous == null) 0L
            else (minIntervalMs - (now() - previous)).coerceAtLeast(0L)
            // Reserved before the wait, so two callers cannot both measure against the same past
            // request and then leave together.
            lastStartedAt = now() + remaining
            remaining
        }
        if (wait > 0) sleep(wait)
    }

    companion object {
        /**
         * Four requests a second. Steam's storefront endpoints are keyless and undocumented, so
         * the budget is chosen to be obviously polite rather than to be the measured maximum —
         * there is no published limit to tune against, and a bulk scan is not time-critical.
         */
        const val DEFAULT_INTERVAL_MS = 250L
    }
}

/**
 * Title searches remembered for a while (Phase 14), kept strictly apart from confirmed identities.
 *
 * The separation is the requirement: a search answer goes stale (a store adds a game, renames one,
 * delists one) and must expire, while a CONFIRMED id does not go stale at all and lives in
 * `game_storefront_identities` where nothing expires it. An expiring search cache must never be
 * able to cost a game its identity.
 *
 * In memory rather than on disk, and deliberately not [TitleSearchStore]: that store is the Artwork
 * Studio's, keyed by [MatchProvider] and holding [GameCandidate], which carries no developer or
 * publisher. Reusing it would silently drop the two corroborating signals the scorer needs most,
 * so the cache that feeds the scorer holds the scorer's own type. Losing it costs one request.
 */
class StorefrontSearchCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val candidates: List<StorefrontCandidate>, val expiresAt: Long)

    // Access-ordered so the eviction below drops the least recently USED entry, not the oldest
    // written one: a query a bulk run keeps returning to is the one worth keeping.
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > maxEntries
    }

    private val lock = Any()

    fun get(store: Storefront, query: String): List<StorefrontCandidate>? = synchronized(lock) {
        val entry = entries[keyOf(store, query)] ?: return null
        if (entry.expiresAt <= now()) {
            entries.remove(keyOf(store, query))
            return null
        }
        entry.candidates
    }

    /** Remembers an answer, including an empty one — "this store has no such game" is an answer. */
    fun put(store: Storefront, query: String, candidates: List<StorefrontCandidate>) {
        synchronized(lock) { entries[keyOf(store, query)] = Entry(candidates, now() + ttlMs) }
    }

    fun clear() = synchronized(lock) { entries.clear() }

    private fun keyOf(store: Storefront, query: String) = "${store.key}\u0000$query"

    companion object {
        /** Long enough to cover one library scan and a few retries, short enough to go stale. */
        const val DEFAULT_TTL_MS = 6L * 60 * 60 * 1000
        const val DEFAULT_MAX_ENTRIES = 500
    }
}
