package com.playfieldportal.feature.achievements.provider.retro

import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Credential-gate coverage for the isolated RA remote island. The success/error mapping is exercised
 * end to end in Phase 1 once the mapper sits behind the provider-neutral RemoteAchievementSource
 * seam — testing it here would mean hand-building api-kotlin's POJOs and a Retrofit-backed
 * NetworkResponse, which belongs behind that seam, not against the raw client.
 */
class RaRemoteDataSourceTest {

    private val factory = mockk<RaClientFactory>()
    private val dataSource = RaRemoteDataSource(factory)

    @Test
    fun `fetch returns MissingCredentials when RA is not connected`() = runTest {
        coEvery { factory.session() } returns null
        assertEquals(ProviderSyncResult.MissingCredentials, dataSource.fetch("1234"))
    }

    @Test
    fun `hashMap returns null (not an empty map) when RA is not connected`() = runTest {
        // Null = "couldn't fetch" — an empty map here used to get cached by RaHashResolver and
        // permanently report every hash on the console as unregistered.
        coEvery { factory.session() } returns null
        assertEquals(null, dataSource.hashMap(7))
    }
}
