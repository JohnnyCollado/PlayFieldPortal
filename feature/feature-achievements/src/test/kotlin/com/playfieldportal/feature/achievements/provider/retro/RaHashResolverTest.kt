package com.playfieldportal.feature.achievements.provider.retro

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RaHashResolverTest {

    private val remote = mockk<RaRemoteDataSource>()
    private val resolver = RaHashResolver(remote)

    @Test
    fun `a failed fetch is not cached — the next lookup retries and can succeed`() = runTest {
        // First call: list unavailable (offline / no credentials yet).
        coEvery { remote.hashMap(3) } returns null
        assertEquals(RaHashLookup.Unavailable, resolver.lookup(3, "AABB"))

        // Credentials arrive / network returns: the same resolver instance must retry, not serve
        // a poisoned empty cache that reports every hash as unregistered.
        coEvery { remote.hashMap(3) } returns mapOf("aabb" to "999")
        assertEquals(RaHashLookup.Found("999"), resolver.lookup(3, "AABB"))
    }

    @Test
    fun `a successful fetch is cached per console and lookups are case-insensitive`() = runTest {
        coEvery { remote.hashMap(3) } returns mapOf("aabb" to "999")

        assertEquals(RaHashLookup.Found("999"), resolver.lookup(3, "AABB"))
        assertEquals(RaHashLookup.NotRegistered, resolver.lookup(3, "ffff"))

        coVerify(exactly = 1) { remote.hashMap(3) }
    }
}
