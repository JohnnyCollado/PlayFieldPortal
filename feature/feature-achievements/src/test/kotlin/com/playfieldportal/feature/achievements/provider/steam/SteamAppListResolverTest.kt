package com.playfieldportal.feature.achievements.provider.steam

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class SteamAppListResolverTest {

    private val storeApi = mockk<SteamStoreApi>()
    private val resolver = SteamAppListResolver(storeApi)

    private fun results(vararg items: StoreItem) {
        coEvery { storeApi.search(any(), any(), any()) } returns
            Response.success(StoreSearchResponse(items.toList()))
    }

    @Test
    fun `resolveAppId links only on an exact normalized name match`() = runTest {
        results(
            StoreItem(id = 620, name = "Portal 2", type = "app"),
            StoreItem(id = 400, name = "Portal", type = "app"),
        )
        assertEquals("400", resolver.resolveAppId("PORTAL"))       // case-insensitive exact
        assertEquals("620", resolver.resolveAppId("Portal  2"))    // punctuation/space-insensitive
        assertNull(resolver.resolveAppId("Portal 3"))              // fuzzy result never mislinks
    }

    @Test
    fun `search returns ranked app candidates and skips non-apps`() = runTest {
        results(
            StoreItem(id = 1, name = "Half-Life", type = "app"),
            StoreItem(id = 2, name = "Half-Life OST", type = "music"),
            StoreItem(id = 3, name = "Half-Life 2", type = "app"),
        )
        val candidates = resolver.search("half life")
        assertEquals(listOf("1", "3"), candidates.map { it.appId })
        assertEquals("Half-Life", candidates.first().name)
    }

    @Test
    fun `a failed store call resolves to nothing`() = runTest {
        coEvery { storeApi.search(any(), any(), any()) } throws RuntimeException("boom")
        assertNull(resolver.resolveAppId("Portal"))
        assertEquals(emptyList<SteamCandidate>(), resolver.search("Portal"))
    }
}
