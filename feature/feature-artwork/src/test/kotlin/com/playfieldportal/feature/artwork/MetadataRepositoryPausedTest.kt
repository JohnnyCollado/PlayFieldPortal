package com.playfieldportal.feature.artwork

import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.feature.artwork.api.ArtworkFolderState
import com.playfieldportal.feature.artwork.api.IgdbApi
import com.playfieldportal.feature.artwork.api.ScrapeOptions
import com.playfieldportal.feature.artwork.api.ScreenScraperApi
import com.playfieldportal.feature.artwork.api.SgdbApiKeyProvider
import com.playfieldportal.feature.artwork.api.folderStatusOf
import com.playfieldportal.feature.artwork.api.readyFolder
import com.playfieldportal.feature.artwork.api.unavailableFolder
import com.playfieldportal.feature.artwork.store.ArtworkKind
import com.playfieldportal.feature.artwork.store.ArtworkStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AD-5: while the artwork folder is not Ready, a scrape still writes the text metadata but passes
 * the artwork columns as null. A remote URL written there would read as "valid forever" and hide
 * the game from Scrape Missing once the folder comes back.
 */
class MetadataRepositoryPausedTest {

    private val folder = MutableStateFlow<ArtworkFolderState>(readyFolder)
    private val gameDao = mockk<GameDao>(relaxed = true)
    private val artworkStore = mockk<ArtworkStore>()
    private val theGamesDb = mockk<TheGamesDbApi>()
    private val screenScraper = mockk<ScreenScraperApi>(relaxed = true)
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
        artworkStore = artworkStore,
        httpClient = mockk(relaxed = true),
        videoSnapTranscoder = mockk(relaxed = true),
        ssMediaCacheDao = mockk(relaxed = true),
        scrapePreferences = mockk(relaxed = true),
        storefrontResolver = mockk(relaxed = true),
        folderStatus = folderStatusOf(folder),
    )

    private fun givenGameWithRemoteArt() {
        coEvery { gameDao.getById(1L) } returns GameEntity(
            id = 1L, title = "crash", platformId = "psx", romPath = null,
            packageName = null, emulatorPackage = null, artworkUri = null, heroUri = null,
            logoUri = null, description = null, developer = null, publisher = null,
            releaseYear = null, genre = null, steamGridDbId = null,
        )
        coEvery { screenScraper.isEnabled() } returns false
        coEvery { sgdbKeyProvider.getKey() } returns null
        coEvery { igdbApi.hasCredentials() } returns false
        coEvery { theGamesDb.fetchGameInfo(any(), any()) } returns TgdbGameInfo(
            tgdbId = 7L, title = "Crash", description = "A bandicoot.", releaseYear = 1996,
            artworkUrl = "https://cdn/box.png", heroUrl = "https://cdn/hero.png", logoUrl = "https://cdn/logo.png",
        )
    }

    @Test
    fun `a folder lost during the asset phase writes text only and reports paused`() = runTest {
        givenGameWithRemoteArt()
        coEvery { artworkStore.saveFromUrl(any(), any(), any()) } coAnswers {
            folder.value = unavailableFolder   // the store refreshed and found the grant gone
            null
        }

        val result = repo.fetchForGame(1L, "crash", "psx", romPath = null)

        assertTrue(result.paused)
        // No remote URL stands in for the artwork: the artwork columns are null (COALESCE keeps old).
        coVerify { gameDao.updateMetadata(id = 1L, description = "A bandicoot.", releaseYear = 1996, tgdbId = 7L) }
    }

    @Test
    fun `a ready folder still falls back to the remote URL when one download fails`() = runTest {
        givenGameWithRemoteArt()
        coEvery { artworkStore.saveFromUrl(any(), ArtworkKind.HERO, any()) } returns null
        coEvery { artworkStore.saveFromUrl(any(), neq(ArtworkKind.HERO), any()) } returns "file:///saved.png"

        val result = repo.fetchForGame(1L, "crash", "psx", romPath = null)

        assertFalse(result.paused)
        coVerify {
            gameDao.updateMetadata(
                id = 1L, description = "A bandicoot.", releaseYear = 1996,
                artworkUri = "file:///saved.png", heroUri = "https://cdn/hero.png",
                logoUri = "file:///saved.png", boxArtUri = "file:///saved.png", tgdbId = 7L,
            )
        }
    }

    @Test
    fun `a metadata-only run is never paused`() = runTest {
        givenGameWithRemoteArt()
        folder.value = unavailableFolder

        val result = repo.fetchForGame(1L, "crash", "psx", romPath = null, options = ScrapeOptions(metadataOnly = true))

        assertFalse(result.paused)
    }
}
