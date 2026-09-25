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
        scrapePreferences = mockk(relaxed = true),
        // C23 T6: relaxed, so these cases keep pinning the four providers they were written for.
        // The storefront resolver's own order of operations is pinned by its own suite.
        storefrontResolver = mockk(relaxed = true),
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

    private fun givenGame(
        userTitleOverride: String? = null,
        storefront: String? = null,
        storefrontGameId: String? = null,
        igdbId: Long? = null,
    ) {
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
            igdbId = igdbId,
            userTitleOverride = userTitleOverride,
            storefront = storefront,
            storefrontGameId = storefrontGameId,
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

        // SteamGridDB supplies no text and is still skipped. IGDB is not on this list any more —
        // since C23 T5 it supplies text, so skipping it here would mean the metadata preview could
        // never offer an IGDB preset.
        assertNull(candidates.sgdbGameId)
        coVerify(exactly = 0) { steamGridDb.searchGame(any()) }
    }

    @Test
    fun `metadata-only retrieval does ask IGDB, which now supplies text`() = runTest {
        givenGame()
        coEvery { igdbApi.hasCredentials() } returns true
        coEvery { igdbApi.fetchGameInfo(any(), any()) } returns
            IgdbGameInfo(artworkUrl = null, heroUrl = null, logoUrl = null, description = "IGDB blurb")

        val candidates = repo.fetchCandidates(
            1L, "raw_rom_name", "snes", romPath = null,
            options = ScrapeOptions(metadataOnly = true),
        )

        assertEquals("IGDB blurb", candidates.igdbInfo?.description)
        coVerify { igdbApi.fetchGameInfo("snes", "raw_rom_name") }
    }

    // ── IGDB by storefront identity (C23 T4) ────────────────────────────────

    @Test
    fun `a Windows game resolves through its storefront id, with no title comparison`() = runTest {
        givenGame(storefront = "STEAM", storefrontGameId = "620")
        coEvery { igdbApi.hasCredentials() } returns true
        coEvery { igdbApi.fetchGameIdByStorefront("STEAM", "620") } returns 7346L
        coEvery { igdbApi.fetchGameInfoById(7346L) } returns
            IgdbGameInfo(artworkUrl = null, heroUrl = null, logoUrl = null, title = "Portal 2")

        val candidates = repo.fetchCandidates(
            1L, "raw_rom_name", "windows", romPath = null,
            options = ScrapeOptions(metadataOnly = true),
        )

        assertEquals("Portal 2", candidates.igdbInfo?.title)
        coVerify { igdbApi.fetchGameInfoById(7346L) }
        // The exact id stands on its own: the title search is never reached.
        coVerify(exactly = 0) { igdbApi.fetchGameInfo(any(), any()) }
    }

    @Test
    fun `a saved igdb id outranks the storefront lookup`() = runTest {
        givenGame(storefront = "STEAM", storefrontGameId = "620", igdbId = 11L)
        coEvery { igdbApi.hasCredentials() } returns true
        coEvery { igdbApi.fetchGameInfoById(11L) } returns
            IgdbGameInfo(artworkUrl = null, heroUrl = null, logoUrl = null, title = "Confirmed match")

        repo.fetchCandidates(
            1L, "raw_rom_name", "windows", romPath = null,
            options = ScrapeOptions(metadataOnly = true),
        )

        // A saved id is a decision the user or a past scrape already made.
        coVerify { igdbApi.fetchGameInfoById(11L) }
        coVerify(exactly = 0) { igdbApi.fetchGameIdByStorefront(any(), any()) }
    }

    @Test
    fun `a row with no storefront pair falls back to the title search exactly as before`() = runTest {
        givenGame()
        coEvery { igdbApi.hasCredentials() } returns true

        repo.fetchCandidates(
            1L, "raw_rom_name", "snes", romPath = null,
            options = ScrapeOptions(metadataOnly = true),
        )

        coVerify { igdbApi.fetchGameInfo("snes", "raw_rom_name") }
        coVerify(exactly = 0) { igdbApi.fetchGameInfoById(any()) }
    }

    @Test
    fun `an id IGDB does not know falls back to the title search`() = runTest {
        givenGame(storefront = "EPIC", storefrontGameId = "unknown-slug")
        coEvery { igdbApi.hasCredentials() } returns true
        coEvery { igdbApi.fetchGameIdByStorefront("EPIC", "unknown-slug") } returns null

        repo.fetchCandidates(
            1L, "raw_rom_name", "windows", romPath = null,
            options = ScrapeOptions(metadataOnly = true),
        )

        coVerify { igdbApi.fetchGameInfo("windows", "raw_rom_name") }
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
                // NOT the title: a scrape may FILL it but never OVERWRITE it, so it is written
                // by fillScrapedTitleIfMissing below rather than riding the COALESCE update —
                // otherwise a re-scrape or a Change Match would silently rename the game.
                scrapedTitle = null,
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
        // The title's own write: fill-only, and skipped entirely when the user set an override.
        coVerify(exactly = 1) { gameDao.fillScrapedTitleIfMissing(1L, "Tgdb Title") }
    }
}
