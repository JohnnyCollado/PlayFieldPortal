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
        val cached = CachingMatchEvidence(provider)

        repeat(3) { cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "windows") }
        // Case and spacing alone do not make a new search.
        cached.searchByTitle(MatchProvider.SCREENSCRAPER, "  tactics   OGRE ", "windows")

        assertEquals(1, provider.asked.size)
    }

    @Test
    fun `provider, platform and punctuation each make a separate search`() = runTest {
        val provider = CountingEvidence()
        val cached = CachingMatchEvidence(provider)

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
        val cached = CachingMatchEvidence(provider)
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
        val cached = CachingMatchEvidence(provider)

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
        val cached = CachingMatchEvidence(provider)

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
        val cached = CachingMatchEvidence(provider)

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
        val cached = CachingMatchEvidence(provider)
        cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "windows")

        cached.clear()
        cached.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "windows")

        assertEquals(2, provider.asked.size)
    }

    @Test
    fun `lookups other than title search are never remembered`() = runTest {
        val provider = CountingEvidence()
        val cached = CachingMatchEvidence(provider)

        repeat(2) { cached.candidateByRomHash(MatchProvider.SCREENSCRAPER, "ABCD1234", "psx") }

        assertEquals(2, provider.asked.size)
    }

    @Test
    fun `a search cancelled mid-flight is not remembered as no hits`() = runTest {
        val provider = CountingEvidence().apply { gate = CompletableDeferred() }
        val cached = CachingMatchEvidence(provider)

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
