package com.playfieldportal.feature.artwork

import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.feature.artwork.api.IgdbApi
import com.playfieldportal.feature.artwork.api.IgdbGameInfo
import com.playfieldportal.feature.artwork.api.ScrapeOptions
import com.playfieldportal.feature.artwork.api.ScreenScraperApi
import com.playfieldportal.feature.artwork.api.SgdbApiKeyProvider
import com.playfieldportal.feature.artwork.api.SteamGridDbApi
import com.playfieldportal.feature.artwork.store.ArtworkStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C16 task 3.1 — `fetchForGame`'s provider steps extracted into a write-free `fetchCandidates`
 * at the existing "nothing found" seam (AD-12).
 *
 * Two promises are pinned: retrieval never touches a `games` column or an artwork file, and the
 * batch scraper's behaviour is unchanged by the split.
 */
class MetadataRepositoryCandidatesTest {

    private val gameDao = mockk<GameDao>(relaxed = true)
    private val screenScraper = mockk<ScreenScraperApi>(relaxed = true)
    private val theGamesDb = mockk<TheGamesDbApi>(relaxed = true)
    private val steamGridDb = mockk<SteamGridDbApi>(relaxed = true)
    private val igdbApi = mockk<IgdbApi>(relaxed = true)
    private val sgdbKeyProvider = mockk<SgdbApiKeyProvider>(relaxed = true)
    private val artworkStore = mockk<ArtworkStore>(relaxed = true)

    private val repo = MetadataRepository(
        context = mockk(relaxed = true),
        gameDao = gameDao,
        screenScraper = screenScraper,
        romHasher = mockk(relaxed = true),
        theGamesDb = theGamesDb,
        steamGridDb = steamGridDb,
        igdbApi = igdbApi,
        sgdbKeyProvider = sgdbKeyProvider,
        imageLoader = mockk(relaxed = true),
        artworkStore = artworkStore,
        httpClient = mockk(relaxed = true),
        videoSnapTranscoder = mockk(relaxed = true),
        ssMediaCacheDao = mockk(relaxed = true),
    )

    private val tgdb = TgdbGameInfo(
        tgdbId = 7L,
        title = "Tgdb Title",
        description = "A description",
        releaseYear = 1994,
        artworkUrl = null,
        heroUrl = null,
        logoUrl = null,
    )

    private fun givenGame(userTitleOverride: String? = null) {
        coEvery { gameDao.getById(1L) } returns GameEntity(
            id = 1L,
            title = "raw_rom_name",
            platformId = "snes",
            romPath = null,
            packageName = null,
            emulatorPackage = null,
            artworkUri = null,
            heroUri = null,
            logoUri = null,
            description = null,
            developer = null,
            publisher = null,
            releaseYear = null,
            genre = null,
            steamGridDbId = null,
            userTitleOverride = userTitleOverride,
        )
        coEvery { screenScraper.isEnabled() } returns false
        coEvery { sgdbKeyProvider.getKey() } returns null
    }

    @Test
    fun `fetchCandidates writes no game column and saves no artwork`() = runTest {
        givenGame()
        coEvery { theGamesDb.fetchGameInfo(any(), any()) } returns tgdb
        coEvery { igdbApi.hasCredentials() } returns true
        coEvery { igdbApi.fetchGameInfo(any(), any()) } returns
            IgdbGameInfo(artworkUrl = "https://igdb/cover.jpg", heroUrl = null, logoUrl = null)

        val candidates = repo.fetchCandidates(1L, "raw_rom_name", "snes", romPath = null)

        assertEquals(tgdb, candidates.tgdbInfo)
        assertEquals("https://igdb/cover.jpg", candidates.igdbInfo?.artworkUrl)
        assertFalse(candidates.isEmpty)
        // The only thing retrieval may ask of the games table is to READ the row.
        coVerify(exactly = 1) { gameDao.getById(1L) }
        confirmVerified(gameDao)
        confirmVerified(artworkStore)
    }

    @Test
    fun `fetchCandidates searches by the user's title override, not the raw title`() = runTest {
        givenGame(userTitleOverride = "Chrono Trigger")

        val candidates = repo.fetchCandidates(1L, "raw_rom_name", "snes", romPath = null)

        assertEquals("Chrono Trigger", candidates.bestTitle)
        coVerify { theGamesDb.fetchGameInfo(platformId = "snes", title = "Chrono Trigger") }
    }

    @Test
    fun `metadata-only retrieval never asks the artwork-only providers`() = runTest {
        givenGame()
        coEvery { igdbApi.hasCredentials() } returns true
        coEvery { sgdbKeyProvider.getKey() } returns "sgdb-key"

        val candidates = repo.fetchCandidates(
            1L, "raw_rom_name", "snes", romPath = null,
            options = ScrapeOptions(metadataOnly = true),
        )

        assertNull(candidates.igdbInfo)
        assertNull(candidates.sgdbGameId)
        coVerify(exactly = 0) { igdbApi.fetchGameInfo(any(), any()) }
        coVerify(exactly = 0) { steamGridDb.searchGame(any()) }
    }

    @Test
    fun `nothing found is empty candidates, and fetchForGame still writes nothing`() = runTest {
        givenGame()
        coEvery { theGamesDb.fetchGameInfo(any(), any()) } returns null

        assertTrue(repo.fetchCandidates(1L, "raw_rom_name", "snes", romPath = null).isEmpty)

        val result = repo.fetchForGame(1L, "raw_rom_name", "snes", romPath = null)

        assertFalse(result.success)
        assertEquals("none", result.source)
        coVerify(exactly = 2) { gameDao.getById(1L) }
        confirmVerified(gameDao)
        confirmVerified(artworkStore)
    }

    @Test
    fun `fetchForGame still persists the winners through the COALESCE write`() = runTest {
        givenGame()
        coEvery { theGamesDb.fetchGameInfo(any(), any()) } returns tgdb

        val result = repo.fetchForGame(
            1L, "raw_rom_name", "snes", romPath = null,
            options = ScrapeOptions(metadataOnly = true),
        )

        assertTrue(result.success)
        assertEquals("thegamesdb", result.source)
        assertEquals("Tgdb Title", result.scrapedTitle)
        coVerify(exactly = 1) {
            gameDao.updateMetadata(
                id = 1L,
                description = "A description",
                developer = null,
                publisher = null,
                releaseYear = 1994,
                genre = null,
                artworkUri = null,
                heroUri = null,
                logoUri = null,
                iconUri = null,
                boxArtUri = null,
                physicalMediaUri = null,
                box3dUri = null,
                scrapedTitle = "Tgdb Title",
                players = null,
                ageRating = null,
                franchise = null,
                communityRating = null,
                releaseDate = null,
                ssId = null,
                tgdbId = 7L,
                igdbId = null,
                steamGridDbId = null,
                romCrc32 = null,
            )
        }
    }
}
