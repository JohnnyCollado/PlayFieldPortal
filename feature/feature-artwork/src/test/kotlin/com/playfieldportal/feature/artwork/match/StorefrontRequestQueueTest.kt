package com.playfieldportal.feature.artwork.match

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Request discipline and the search cache (C23 T6, Phases 14 and 16).
 *
 * The two exist for the two halves of one problem: the queue stops fifty games asking the same
 * question AT ONCE, and the cache stops them asking it again AFTERWARDS.
 */
class StorefrontRequestQueueTest {

    private fun queue(
        intervalMs: Long = 0,
        slept: MutableList<Long> = mutableListOf(),
        clock: () -> Long = { 0L },
    ) = StorefrontRequestQueue(minIntervalMs = intervalMs, now = clock, sleep = { slept += it })

    @Test
    fun `one request runs and its outcome is returned`() = runTest {
        val outcome = queue().submit("k") { StorefrontOutcome.Ok("value") }

        assertEquals("value", (outcome as StorefrontOutcome.Ok).value)
    }

    @Test
    fun `fifty games asking the same question cost one request`() = runTest {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val queue = queue()

        val started = List(50) {
            async {
                queue.submit("search:the witcher 3 wild hunt") {
                    calls.incrementAndGet()
                    gate.await()
                    StorefrontOutcome.Ok("witcher")
                }
            }
        }
        // Every coroutine reaches the gate before any of them is allowed past it — which is what
        // makes this the in-flight case rather than fifty sequential requests.
        advanceUntilIdle()
        gate.complete(Unit)
        val results = started.map { it.await() }

        assertEquals(1, calls.get())
        assertTrue(results.all { (it as StorefrontOutcome.Ok).value == "witcher" })
    }

    @Test
    fun `different questions are not deduplicated into one another`() = runTest {
        val calls = AtomicInteger()
        val queue = queue()

        queue.submit("search:doom") { StorefrontOutcome.Ok(calls.incrementAndGet()) }
        queue.submit("search:doom ii") { StorefrontOutcome.Ok(calls.incrementAndGet()) }

        assertEquals(2, calls.get())
    }

    @Test
    fun `the same key asked again after the first finished issues a new request`() = runTest {
        // Deduplication is about requests IN FLIGHT. Remembering an answer is the cache's job, and
        // conflating the two would make the queue a cache with no expiry.
        val calls = AtomicInteger()
        val queue = queue()

        queue.submit("k") { StorefrontOutcome.Ok(calls.incrementAndGet()) }
        queue.submit("k") { StorefrontOutcome.Ok(calls.incrementAndGet()) }

        assertEquals(2, calls.get())
    }

    @Test
    fun `requests are spaced by the configured interval`() = runTest {
        val slept = mutableListOf<Long>()
        var now = 0L
        val queue = queue(intervalMs = 250, slept = slept, clock = { now })

        queue.submit("a") { StorefrontOutcome.Ok(Unit) }
        queue.submit("b") { StorefrontOutcome.Ok(Unit) }

        // The first goes straight out; the second waits out the gap rather than doubling the rate.
        assertEquals(listOf(250L), slept.filter { it > 0 })
    }

    @Test
    fun `a throwing request releases everyone waiting on it`() = runTest {
        val queue = queue()
        val gate = CompletableDeferred<Unit>()

        val first = async {
            runCatching {
                queue.submit<String>("k") {
                    gate.await()
                    throw IllegalStateException("provider bug")
                }
            }
        }
        advanceUntilIdle()
        // Joins the in-flight request rather than starting its own.
        val second = async { runCatching { queue.submit<String>("k") { StorefrontOutcome.Ok("b") } } }
        advanceUntilIdle()
        gate.complete(Unit)
        val outcomes = listOf(first.await(), second.await())

        // The owner's exception reaches its own caller and nobody else is left hanging — which is
        // the property that stops one bug from stalling every other game in a bulk run.
        assertTrue(outcomes[0].isFailure)
        val joined = outcomes[1].getOrNull()
        assertEquals(StorefrontFailure.PROVIDER_ERROR, (joined as StorefrontOutcome.Failure).reason)
    }
}

/** The search cache's own rules — Phase 14's separation of a stale search from a stored identity. */
class StorefrontSearchCacheTest {

    private fun candidate(id: String) = StorefrontCandidate(Storefront.STEAM, id, "Portal $id")

    @Test
    fun `an answer is returned until it expires and then forgotten`() {
        var now = 0L
        val cache = StorefrontSearchCache(ttlMs = 100, now = { now })

        cache.put(Storefront.STEAM, "portal 2", listOf(candidate("620")))
        assertEquals(1, cache.get(Storefront.STEAM, "portal 2")!!.size)

        now = 101
        assertNull(cache.get(Storefront.STEAM, "portal 2"))
    }

    @Test
    fun `an empty answer is remembered, because it is an answer`() {
        val cache = StorefrontSearchCache(now = { 0L })

        cache.put(Storefront.STEAM, "nothing sells this", emptyList())

        // Not null: "Steam has nothing for this title" is worth not asking twice in one scan.
        assertEquals(emptyList<StorefrontCandidate>(), cache.get(Storefront.STEAM, "nothing sells this"))
    }

    @Test
    fun `two stores never read each other's answers`() {
        val cache = StorefrontSearchCache(now = { 0L })

        cache.put(Storefront.STEAM, "doom", listOf(candidate("379720")))

        assertNull(cache.get(Storefront.GOG, "doom"))
    }

    @Test
    fun `the cache is bounded`() {
        val cache = StorefrontSearchCache(maxEntries = 2, now = { 0L })

        cache.put(Storefront.STEAM, "a", listOf(candidate("1")))
        cache.put(Storefront.STEAM, "b", listOf(candidate("2")))
        cache.put(Storefront.STEAM, "c", listOf(candidate("3")))

        assertNull(cache.get(Storefront.STEAM, "a"))
        assertEquals(1, cache.get(Storefront.STEAM, "c")!!.size)
    }
}
