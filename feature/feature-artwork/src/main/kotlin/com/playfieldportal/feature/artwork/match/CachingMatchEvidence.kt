package com.playfieldportal.feature.artwork.match

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers title searches, and shares any search still in flight.
 *
 * The matcher re-runs Tier 3 on every source switch, tab switch and search, and the picker searches
 * the same titles again. ScreenScraper serves this account one request at a time, about three
 * seconds for a platform search and ten for every platform, so each repeat queued behind the last.
 * Now a search is asked once: a caller that arrives while it runs waits for the same answer, and a
 * later caller gets it from memory. Only title searches are remembered; the other lookups pass
 * straight through.
 *
 * Memory lasts one Studio open ([clear]). Behind it, [store] keeps answers between opens (AD-21):
 * an answer with hits for [HITS_TTL_MS], and ScreenScraper's empty answer for [SCREENSCRAPER_EMPTY_TTL_MS].
 * ScreenScraper throws when a search fails, so its empty answer is a real one, but a game it adds
 * later should not stay hidden for a week. The other providers still report a failure as an empty
 * list, so their empty answers are never kept, and a failure is never remembered anywhere.
 */
class CachingMatchEvidence(
    private val delegate: MatchEvidenceSource,
    private val store: TitleSearchStore,
    private val now: () -> Long = System::currentTimeMillis,
) : MatchEvidenceSource by delegate {

    private val answers = ConcurrentHashMap<SearchKey, List<GameCandidate>>()
    private val inFlight = ConcurrentHashMap<SearchKey, CompletableDeferred<List<GameCandidate>>>()

    override suspend fun searchByTitle(
        provider: MatchProvider,
        query: String,
        platformId: String,
    ): List<GameCandidate> =
        remember(provider, query, scope = platformId) { delegate.searchByTitle(provider, query, platformId) }

    /**
     * [search]'s answer, asked at most once per [provider], [query] and [scope] once it succeeds.
     * [scope] names what the search covers: the platform id for [searchByTitle], or a label of the
     * caller's own for a search this interface has no method for. It is kept apart in [store] too.
     */
    suspend fun remember(
        provider: MatchProvider,
        query: String,
        scope: String,
        search: suspend () -> List<GameCandidate>,
    ): List<GameCandidate> {
        val key = SearchKey.of(provider, query, scope)
        while (true) {
            answers[key]?.let { return it }
            val mine = CompletableDeferred<List<GameCandidate>>()
            val running = inFlight.putIfAbsent(key, mine)
            if (running == null) {
                // The search that answered can finish between the check above and claiming its slot.
                // Its answer is stored before its slot is freed, so look once more before asking.
                answers[key]?.let { answer ->
                    mine.complete(answer)
                    inFlight.remove(key, mine)
                    return answer
                }
                return ask(key, mine, search)
            }
            try {
                return running.await()
            } catch (e: CancellationException) {
                // Either this caller was cancelled, which ends it here, or the caller asking was,
                // which leaves no answer coming: drop that search and ask again.
                currentCoroutineContext().ensureActive()
                inFlight.remove(key, running)
            }
        }
    }

    /** Forgets every answer in memory, so the next open asks afresh. What [store] keeps stays. */
    fun clear() = answers.clear()

    private suspend fun ask(
        key: SearchKey,
        mine: CompletableDeferred<List<GameCandidate>>,
        search: suspend () -> List<GameCandidate>,
    ): List<GameCandidate> {
        val (found, fromProvider) = try {
            // Callers arriving meanwhile wait on this slot, so they share the store read too.
            val kept = store.read(key.provider, key.query, key.scope)?.takeIf { it.expiresAtMillis > now() }
            val answer = kept?.candidates ?: search()
            // A cancelled search is not an answer: some provider clients turn the cancellation into
            // an empty list, which would otherwise be remembered as "no hits".
            currentCoroutineContext().ensureActive()
            answers[key] = answer
            mine.complete(answer)
            answer to (kept == null)
        } catch (e: Throwable) {
            // Callers waiting on this search get the same failure; nothing is remembered.
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key, mine)
        }
        if (fromProvider) keep(key, found)
        return found
    }

    /** Hands a provider's answer to [store] when AD-21 says it is worth keeping. */
    private suspend fun keep(key: SearchKey, found: List<GameCandidate>) {
        val ttl = when {
            found.isNotEmpty() -> HITS_TTL_MS
            key.provider == MatchProvider.SCREENSCRAPER -> SCREENSCRAPER_EMPTY_TTL_MS
            else -> return
        }
        store.write(key.provider, key.query, key.scope, StoredTitleSearch(found, now() + ttl))
    }

    private data class SearchKey(val provider: MatchProvider, val query: String, val scope: String) {
        companion object {
            private val WHITESPACE = Regex("\\s+")

            // Case and spacing only. Punctuation can change what a provider returns, so
            // "Tactics Ogre: Reborn" and "Tactics Ogre Reborn" stay two searches.
            fun of(provider: MatchProvider, query: String, scope: String) = SearchKey(
                provider = provider,
                query = query.trim().replace(WHITESPACE, " ").lowercase(Locale.ROOT),
                scope = scope,
            )
        }
    }

    companion object {
        const val HITS_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val SCREENSCRAPER_EMPTY_TTL_MS = 24L * 60 * 60 * 1000
    }
}
