package com.playfieldportal.feature.artwork.match

import com.playfieldportal.feature.artwork.api.ScreenScraperApi
import com.playfieldportal.feature.artwork.api.SsSearchFailedException
import com.playfieldportal.feature.artwork.api.SsSearchHit
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderMatchEvidenceScreenScraperTest {

    private val screenScraper = mockk<ScreenScraperApi>()
    private val evidence = ProviderMatchEvidence(
        steamGridDb = mockk(relaxed = true),
        screenScraper = screenScraper,
        igdbApi = mockk(relaxed = true),
        theGamesDb = mockk(relaxed = true),
    )

    private fun hit(ssId: Long, systemId: Int, systemName: String) =
        SsSearchHit(ssId = ssId, title = "Tactics Ogre", releaseYear = null, systemId = systemId, systemName = systemName)

    @Test
    fun `an every-platform search lists the game's own platform first, the rest in ScreenScraper's order`() = runTest {
        coEvery { screenScraper.searchGames(null, "Tactics Ogre") } returns listOf(
            hit(2293, systemId = 4, systemName = "Super Nintendo"),
            hit(425726, systemId = 225, systemName = "Switch"),
            hit(9, systemId = ScreenScraperApi.PLATFORM_IDS.getValue("windows"), systemName = "PC Windows"),
            hit(478505, systemId = 284, systemName = "Playstation 5"),
        )

        val candidates = evidence.searchScreenScraperOnAnyPlatform("Tactics Ogre", preferredPlatformId = "windows")

        assertEquals(listOf("9", "2293", "425726", "478505"), candidates.map { it.providerGameId })
        assertEquals("PC Windows", candidates.first().platformName)
    }

    @Test
    fun `only ScreenScraper on Windows skips the platform search`() {
        assertTrue(evidence.searchesEveryPlatformFirst(MatchProvider.SCREENSCRAPER, "windows"))
        assertFalse(evidence.searchesEveryPlatformFirst(MatchProvider.SCREENSCRAPER, "psx"))
        assertFalse(evidence.searchesEveryPlatformFirst(MatchProvider.THEGAMESDB, "windows"))
    }

    @Test
    fun `a failed ScreenScraper search reaches the caller as a failure, not as no hits`() = runTest {
        coEvery { screenScraper.searchGames(any(), any()) } throws SsSearchFailedException("HTTP 429")

        val result = runCatching { evidence.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "psp") }

        assertTrue(result.exceptionOrNull() is SsSearchFailedException)
    }

    @Test
    fun `ScreenScraper is never title-searched on a platform without ROM files`() = runTest {
        coEvery { screenScraper.searchGames(any(), any()) } returns listOf(hit(9, systemId = 138, systemName = "PC Windows"))

        val candidates = evidence.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "windows")

        assertEquals(emptyList<GameCandidate>(), candidates)
        coVerify(exactly = 0) { screenScraper.searchGames(any(), any()) }
    }

    @Test
    fun `other platforms still search ScreenScraper by title`() = runTest {
        coEvery { screenScraper.searchGames("psp", "Tactics Ogre") } returns listOf(hit(2293, systemId = 61, systemName = "PSP"))

        val candidates = evidence.searchByTitle(MatchProvider.SCREENSCRAPER, "Tactics Ogre", "psp")

        assertEquals(listOf("2293"), candidates.map { it.providerGameId })
        coVerify(exactly = 1) { screenScraper.searchGames("psp", "Tactics Ogre") }
    }

    @Test
    fun `each candidate carries how much art of its own its release has`() = runTest {
        coEvery { screenScraper.searchGames(null, "Tactics Ogre") } returns listOf(
            hit(425726, systemId = 225, systemName = "Switch").copy(gameArtCount = 37),
            hit(478505, systemId = 284, systemName = "Playstation 5"),
        )

        val candidates = evidence.searchScreenScraperOnAnyPlatform("Tactics Ogre", preferredPlatformId = "psx")

        assertEquals(listOf(37, 0), candidates.map { it.gameArtCount })
    }
}
