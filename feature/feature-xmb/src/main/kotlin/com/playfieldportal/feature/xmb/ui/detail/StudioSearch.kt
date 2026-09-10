package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.feature.artwork.match.TitleKey
import com.playfieldportal.feature.artwork.store.ArtworkKind

/**
 * The Artwork Studio's search model — pure, so the whole of C16 Phase 1's correctness (what a
 * query means, when two requests are the same request, which response may reach the screen) is
 * unit-testable without a ViewModel, a coroutine or a provider.
 */

/**
 * Normalization used for CACHE KEYING and matching only.
 *
 * It never touches what the user typed: the editable field always holds their exact text, and
 * the game is never renamed by searching. Normalizing is purely so that "Final Fantasy VII",
 * "  final   fantasy vii  " and "Final Fantasy VII (USA)" hit one cache entry instead of three.
 */
object StudioQuery {

    /**
     * The key form of [raw]. Delegates to [TitleKey] so the query that addresses a result cache
     * and the title that resolves a Phase 2 match can never drift apart (task 2.2).
     */
    fun normalize(raw: String): String = TitleKey.of(raw)

    /** True when [a] and [b] address the same results — the test behind "do I need to refetch?". */
    fun sameQuery(a: String, b: String): Boolean = TitleKey.same(a, b)
}

/**
 * Everything that decides WHICH results a request produces, and nothing that does not.
 *
 * Two requests with equal keys are the same request, so one may serve the other from cache; two
 * with different keys are different requests, so a response for one may never reduce into the
 * other's state. That equality is the whole race fix (AD-6): the disappearing-artwork bug was a
 * single shared result list written by whichever unkeyed job happened to finish last.
 *
 * [includeNsfw] is deliberately part of the key, but only SteamGridDB ever sets it — every other
 * source builds its key with `false`, so toggling mature can never invalidate ScreenScraper's or
 * IGDB's cached pages (task 1.3).
 *
 * [matchId] is the confirmed game match the results were fetched for. It is always null today;
 * Phase 2's tiered matcher fills it, and because it is already in the key, changing the match
 * will invalidate exactly the right cache entries without touching this class.
 */
data class StudioRequestKey(
    val normalizedQuery: String,
    val source: StudioSource,
    val kind: ArtworkKind,
    val includeNsfw: Boolean = false,
    val matchId: String? = null,
) {
    companion object {
        /** Builds the key for a browse, applying the source-scoping rules above. */
        fun of(
            query: String,
            source: StudioSource,
            kind: ArtworkKind,
            includeNsfw: Boolean,
            matchId: String? = null,
        ) = StudioRequestKey(
            normalizedQuery = StudioQuery.normalize(query),
            source = source,
            kind = kind,
            // Mature is a SteamGridDB filter and nothing else's business.
            includeNsfw = includeNsfw && source == StudioSource.STEAMGRIDDB,
            matchId = matchId,
        )
    }
}

/**
 * A small LRU of finished result lists, keyed by [StudioRequestKey].
 *
 * Replaces the single `allResults` field: with one list per key, switching back to a source the
 * user already visited is instant and — more importantly — a late response can only ever be
 * stored under its OWN key, never on top of what is currently on screen.
 */
class StudioResultCache(private val maxEntries: Int = MAX_ENTRIES) {

    private val entries = LinkedHashMap<StudioRequestKey, List<StudioArt>>(16, 0.75f, true)

    operator fun get(key: StudioRequestKey): List<StudioArt>? = synchronized(entries) { entries[key] }

    operator fun set(key: StudioRequestKey, results: List<StudioArt>) = synchronized(entries) {
        entries[key] = results
        while (entries.size > maxEntries) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    fun contains(key: StudioRequestKey): Boolean = synchronized(entries) { entries.containsKey(key) }

    /** Drops every entry for [source] — used when its credentials or filters change underneath it. */
    fun evictSource(source: StudioSource) = synchronized(entries) {
        entries.keys.filter { it.source == source }.forEach { entries.remove(it) }
    }

    fun clear() = synchronized(entries) { entries.clear() }

    val size: Int get() = synchronized(entries) { entries.size }

    private companion object {
        // A gridful each, across the handful of category/source pairs a session actually visits.
        const val MAX_ENTRIES = 24
    }
}

/**
 * One page of results, computed from a full list — the client-side paging every provider forces
 * on us (AD-3: none of them support server paging) and a page is exactly one gridful (AD-5).
 */
data class StudioPage(
    val items: List<StudioArt>,
    val pageIndex: Int,
    val pageCount: Int,
    /** 1-based inclusive range of [items] within the whole result list; 0..0 when empty. */
    val rangeStart: Int,
    val rangeEnd: Int,
    val totalResults: Int,
) {
    val hasPrevious: Boolean get() = pageIndex > 0
    val hasNext: Boolean get() = pageIndex < pageCount - 1

    companion object {
        fun of(all: List<StudioArt>, pageIndex: Int, pageSize: Int): StudioPage {
            require(pageSize > 0) { "pageSize must be positive" }
            val pageCount = if (all.isEmpty()) 0 else (all.size + pageSize - 1) / pageSize
            val clamped = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            val from = clamped * pageSize
            val items = if (all.isEmpty()) emptyList() else all.drop(from).take(pageSize)
            return StudioPage(
                items = items,
                pageIndex = clamped,
                pageCount = pageCount,
                rangeStart = if (items.isEmpty()) 0 else from + 1,
                rangeEnd = if (items.isEmpty()) 0 else from + items.size,
                totalResults = all.size,
            )
        }
    }
}
