package com.playfieldportal.feature.artwork.match

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CachingMatchEvidenceTest {

    /** Records every lookup that reached the provider and answers with [answer], or [failure]. */
    private class CountingEvidence : MatchEvidenceSource {
        val asked = mutableListOf<String>()
        var answer: List<GameCandidate> = emptyList()
        var failure: Exception? = null

        // When set, a title search waits here and swallows its own cancellation, as some provider
        // clients do.
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun candidateByRomHash(
            provider: MatchProvider,
            crc32: String,
            platformId: String,
        ): GameCandidate? {
            asked += "rom:$crc32"
            return null
        }

        override suspend fun candidateByStorefront(
            provider: MatchProvider,
            storefront: String,
            storefrontGameId: String,
        ): GameCandidate? {
            asked += "store:$storefront/$storefrontGameId"
            return null
        }

        override suspend fun searchByTitle(
            provider: MatchProvider,
            query: String,
            platformId: String,
        ): List<GameCandidate> {
            asked += "title:$provider/$query/$platformId"
            gate?.let { runCatching { it.await() } }
            failure?.let { throw it }
            return answer
        }
    }

    private val reborn = GameCandidate(MatchProvider.SCREENSCRAPER, "478505", "Tactics Ogre: Reborn")

    @Test
    fun `a repeated search is answered from memory, empty answers included`() = runTest {
        val provider = CountingEvidence()
        val cached = CachingMatchEvidence(provider, TitleSearchStore.None)

        repeat(3) { cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "windows") }
        // Case and spacing alone do not make a new search.
        cached.searchByTitle(MatchProvider.SCREENSCRAPER, "  tactics   OGRE ", "windows")

        assertEquals(1, provider.asked.size)
    }

    @Test
    fun `provider, platform and punctuation each make a separate search`() = runTest {
        val provider = CountingEvidence()
        val cached = CachingMatchEvidence(provider, TitleSearchStore.None)

        cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "windows")
        cached.searchByTitle(MatchProvider.IGDB, "Tactics Ogre", "windows")
        cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "psp")
        cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre: Reborn", "windows")
        cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre Reborn", "windows")

        assertEquals(5, provider.asked.size)
    }

    @Test
    fun `a search under its own scope is remembered apart from the platform search`() = runTest {
        val provider = CountingEvidence()
        val cached = CachingMatchEvidence(provider, TitleSearchStore.None)
        var everyPlatformAsks = 0

        cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "windows")
        repeat(2) {
            cached.remember(MatchProvider.SCREENSCRAPER, "Tactics Ogre", scope = "every-platform:windows") {
                everyPlatformAsks++
                listOf(reborn)
            }
        }

        assertEquals(1, provider.asked.size)
        assertEquals(1, everyPlatformAsks)
    }

    @Test
    fun `a failed search is not remembered, so the next search asks again`() = runTest {
        val provider = CountingEvidence().apply { failure = IllegalStateException("HTTP 429") }
        val cached = CachingMatchEvidence(provider, TitleSearchStore.None)

        val first = runCatching { cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "windows") }
        provider.failure = null
        provider.answer = listOf(reborn)

        assertTrue(first.isFailure)
        assertEquals(listOf(reborn), cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "windows"))
        assertEquals(2, provider.asked.size)
    }

    @Test
    fun `a caller arriving mid-search shares that one request`() = runTest {
        val provider = CountingEvidence().apply { gate = CompletableDeferred(); answer = listOf(reborn) }
        val cached = CachingMatchEvidence(provider, TitleSearchStore.None)

        val first = async { cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "windows") }
        val second = async { cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "windows") }
        runCurrent()
        provider.gate?.complete(Unit)

        assertEquals(listOf(reborn), first.await())
        assertEquals(listOf(reborn), second.await())
        assertEquals(1, provider.asked.size)
    }

    @Test
    fun `when the caller asking is cancelled, a caller waiting on it asks for itself`() = runTest {
        val provider = CountingEvidence().apply { gate = CompletableDeferred(); answer = listOf(reborn) }
        val cached = CachingMatchEvidence(provider, TitleSearchStore.None)

        val asking = launch { cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "windows") }
        runCurrent()
        val waiting = async { cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "windows") }
        runCurrent()
        provider.gate = null   // the waiting caller's own request answers at once
        asking.cancel()

        assertEquals(listOf(reborn), waiting.await())
        assertEquals(2, provider.asked.size)
    }

    @Test
    fun `clearing forgets every search`() = runTest {
        val provider = CountingEvidence()
        val cached = CachingMatchEvidence(provider, TitleSearchStore.None)
        cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "windows")

        cached.clear()
        cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "windows")

        assertEquals(2, provider.asked.size)
    }

    @Test
    fun `lookups other than title search are never remembered`() = runTest {
        val provider = CountingEvidence()
        val cached = CachingMatchEvidence(provider, TitleSearchStore.None)

        repeat(2) { cached.candidateByRomHash(MatchProvider.SCREENSCRAPER, "ABCD1234", "psx") }

        assertEquals(2, provider.asked.size)
    }

    // ── Kept between opens (task M.5, AD-21) ─────────────────────────────────

    /** An in-memory [TitleSearchStore] that records what was written. */
    private class FakeStore : TitleSearchStore {
        val entries = mutableMapOf<Triple<MatchProvider, String, String>, StoredTitleSearch>()

        override suspend fun read(provider: MatchProvider, query: String, scope: String) =
            entries[Triple(provider, query, scope)]

        override suspend fun write(provider: MatchProvider, query: String, scope: String, search: StoredTitleSearch) {
            entries[Triple(provider, query, scope)] = search
        }
    }

    private var clock = 1_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun cachedOver(provider: CountingEvidence, store: TitleSearchStore) =
        CachingMatchEvidence(provider, store, now = { clock })

    @Test
    fun `a search answered in an earlier open is served from the store without asking`() = runTest {
        val store = FakeStore()
        val firstOpen = CountingEvidence().apply { answer = listOf(reborn) }
        cachedOver(firstOpen, store).searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "psp")

        clock += 6 * day
        val nextOpen = CountingEvidence()
        val answer = cachedOver(nextOpen, store).searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "psp")

        assertEquals(listOf(reborn), answer)
        assertTrue(nextOpen.asked.isEmpty())
        // Hits are kept for seven days, under the normalized query.
        assertEquals(1_000_000L + 7 * day, store.entries.getValue(Triple(MatchProvider.SCREENSCRAPER, "tactics ogre", "psp")).expiresAtMillis)
    }

    @Test
    fun `an entry past its TTL is asked again and replaced`() = runTest {
        val store = FakeStore()
        cachedOver(CountingEvidence().apply { answer = listOf(reborn) }, store)
            .searchByTitle(MatchProvider.IGDB, "Tactics Ogre", "psp")

        clock += 7 * day
        val switch = GameCandidate(MatchProvider.IGDB, "425726", "Tactics Ogre: Reborn")
        val nextOpen = CountingEvidence().apply { answer = listOf(switch) }
        val answer = cachedOver(nextOpen, store).searchByTitle(MatchProvider.IGDB, "Tactics Ogre", "psp")

        assertEquals(listOf(switch), answer)
        assertEquals(1, nextOpen.asked.size)
        assertEquals(listOf(switch), store.entries.getValue(Triple(MatchProvider.IGDB, "tactics ogre", "psp")).candidates)
    }

    @Test
    fun `other providers' empty answers are never stored`() = runTest {
        val store = FakeStore()
        val cached = cachedOver(CountingEvidence(), store)

        listOf(MatchProvider.STEAMGRIDDB, MatchProvider.IGDB, MatchProvider.THEGAMESDB).forEach {
            cached.searchByTitle(it, "Tactics Ogre", "psp")
        }

        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun `a ScreenScraper empty answer is stored for one day only`() = runTest {
        val store = FakeStore()
        cachedOver(CountingEvidence(), store).searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "psp")

        assertEquals(clock + day, store.entries.getValue(Triple(MatchProvider.SCREENSCRAPER, "tactics ogre", "psp")).expiresAtMillis)

        clock += day
        val nextOpen = CountingEvidence()
        cachedOver(nextOpen, store).searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "psp")
        assertEquals(1, nextOpen.asked.size)
    }

    @Test
    fun `a failed or cancelled search is never stored`() = runTest {
        val store = FakeStore()
        val failing = CountingEvidence().apply { failure = IllegalStateException("HTTP 429") }
        runCatching { cachedOver(failing, store).searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "psp") }

        val swallowing = CountingEvidence().apply { gate = CompletableDeferred() }
        val job = launch { cachedOver(swallowing, store).searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "psp") }
        runCurrent()
        job.cancel()
        job.join()

        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun `the every-platform scope is stored apart from the platform search`() = runTest {
        val store = FakeStore()
        val cached = cachedOver(CountingEvidence(), store)

        cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "windows")
        cached.remember(MatchProvider.SCREENSCRAPER, "Tactics Ogre", scope = "every-platform:windows") { listOf(reborn) }

        assertEquals(emptyList<GameCandidate>(), store.entries.getValue(Triple(MatchProvider.SCREENSCRAPER, "tactics ogre", "windows")).candidates)
        assertEquals(listOf(reborn), store.entries.getValue(Triple(MatchProvider.SCREENSCRAPER, "tactics ogre", "every-platform:windows")).candidates)
    }

    @Test
    fun `clearing memory keeps what the store holds`() = runTest {
        val store = FakeStore()
        val provider = CountingEvidence().apply { answer = listOf(reborn) }
        val cached = cachedOver(provider, store)
        cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "psp")

        cached.clear()
        cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "psp")

        assertEquals(1, provider.asked.size)
    }

    @Test
    fun `a search cancelled mid-flight is not remembered as no hits`() = runTest {
        val provider = CountingEvidence().apply { gate = CompletableDeferred() }
        val cached = CachingMatchEvidence(provider, TitleSearchStore.None)

        val job = launch { cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "windows") }
        runCurrent()
        job.cancel()
        job.join()

        provider.gate = null
        provider.answer = listOf(reborn)

        assertEquals(listOf(reborn), cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "windows"))
        assertEquals(2, provider.asked.size)
    }
}
