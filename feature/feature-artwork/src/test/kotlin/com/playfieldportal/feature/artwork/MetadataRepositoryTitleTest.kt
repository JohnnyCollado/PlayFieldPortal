package com.playfieldportal.feature.artwork

import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.feature.artwork.api.IgdbApi
import com.playfieldportal.feature.artwork.api.ScrapeOptions
import com.playfieldportal.feature.artwork.api.ScreenScraperApi
import com.playfieldportal.feature.artwork.api.SgdbApiKeyProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * When a scrape may NAME a game (fill `scraped_title`, which the library then shows instead of the
 * stored title). Only the library-wide scrape does, and only for a game the scanner found as a
 * file — its title is a filename. Fetch Artwork never does, and neither does anything that
 * arrived with a real name of its own (PC imports, Add by ID, apps): all of those are manual
 * entries. User decision, 2026-09-29.
 */
class MetadataRepositoryTitleTest {

    private val gameDao = mockk<GameDao>(relaxed = true)
    private val screenScraper = mockk<ScreenScraperApi>(relaxed = true)
    private val theGamesDb = mockk<TheGamesDbApi>(relaxed = true)
    private val igdbApi = mockk<IgdbApi>(relaxed = true)
    private val sgdbKeyProvider = mockk<SgdbApiKeyProvider>(relaxed = true)

    private val repo = MetadataRepository(
        context = mockk(relaxed = true),
        gameDao = gameDao,
        screenScraper = screenScraper,
        romHasher = mockk(relaxed = true),
        theGamesDb = theGamesDb,
        steamGridDb = mockk(relaxed = true),
        igdbApi = igdbApi,
        sgdbKeyProvider = sgdbKeyProvider,
        imageLoader = mockk(relaxed = true),
        artworkStore = mockk(relaxed = true),
        httpClient = mockk(relaxed = true),
        videoSnapTranscoder = mockk(relaxed = true),
        ssMediaCacheDao = mockk(relaxed = true),
        scrapePreferences = mockk(relaxed = true),
        storefrontResolver = mockk(relaxed = true),
    )

    /** A game TheGamesDB knows as "Crash Bandicoot"; nothing has named it yet. */
    private fun givenGame(isManualEntry: Boolean) {
        coEvery { gameDao.getById(1L) } returns GameEntity(
            id = 1L, title = "crash_bandicoot_u", platformId = "psx", romPath = null,
            packageName = null, emulatorPackage = null, artworkUri = null, heroUri = null,
            logoUri = null, description = null, developer = null, publisher = null,
            releaseYear = null, genre = null, steamGridDbId = null, isManualEntry = isManualEntry,
        )
        coEvery { screenScraper.isEnabled() } returns false
        coEvery { sgdbKeyProvider.getKey() } returns null
        coEvery { theGamesDb.fetchGameInfo(any(), any()) } returns TgdbGameInfo(
            tgdbId = 7L, title = "Crash Bandicoot", description = "A bandicoot.", releaseYear = 1996,
            artworkUrl = null, heroUrl = null, logoUrl = null,
        )
    }

    // metadataOnly keeps these cases to the title rule: no artwork is downloaded either way.
    private suspend fun scrape(options: ScrapeOptions) =
        repo.fetchForGame(1L, "crash_bandicoot_u", "psx", romPath = null, options = options.copy(metadataOnly = true))

    @Test
    fun `the library scrape names a game the scanner found as a file`() = runTest {
        givenGame(isManualEntry = false)

        scrape(ScrapeOptions())

        coVerify { gameDao.fillScrapedTitleIfMissing(1L, "Crash Bandicoot") }
    }

    @Test
    fun `the library scrape never names a game that arrived with its own name`() = runTest {
        givenGame(isManualEntry = true)   // a PC import, Add by ID, an app

        scrape(ScrapeOptions())

        coVerify(exactly = 0) { gameDao.fillScrapedTitleIfMissing(any(), any()) }
    }

    @Test
    fun `a scrape told not to name the game never does, even an unnamed scanned one`() = runTest {
        givenGame(isManualEntry = false)

        scrape(ScrapeOptions(fillTitle = false))

        coVerify(exactly = 0) { gameDao.fillScrapedTitleIfMissing(any(), any()) }
    }
}
