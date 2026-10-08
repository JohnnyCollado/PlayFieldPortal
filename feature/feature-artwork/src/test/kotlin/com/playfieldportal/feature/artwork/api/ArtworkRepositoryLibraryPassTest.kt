package com.playfieldportal.feature.artwork.api

import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.feature.artwork.MetadataFetchResult
import com.playfieldportal.feature.artwork.MetadataRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Update Metadata from the All Games card: the per-card pass, over everything All Games shows.
 */
class ArtworkRepositoryLibraryPassTest {

    private val gameDao = mockk<GameDao>()
    private val metadataRepository = mockk<MetadataRepository>(relaxed = true)
    private val scrapePreferences = mockk<ArtworkScrapePreferences>()
    private val folder = MutableStateFlow<ArtworkFolderState>(readyFolder)

    private val repo = ArtworkRepository(
        imageCache = mockk(relaxed = true),
        gameDao = gameDao,
        metadataRepository = metadataRepository,
        scrapePreferences = scrapePreferences,
        artworkStore = mockk(relaxed = true),
        internalStore = mockk(relaxed = true),
        ssMediaCacheDao = mockk(relaxed = true),
        folderStatus = folderStatusOf(folder),
    )

    private fun entity(id: Long, platformId: String, contentType: GameContentType = GameContentType.GAME) =
        GameEntity(
            id = id, title = "game $id", platformId = platformId, romPath = null,
            packageName = null, emulatorPackage = null, artworkUri = null, heroUri = null, logoUri = null,
            description = null, developer = null, publisher = null, releaseYear = null,
            genre = null, steamGridDbId = null, contentType = contentType.name,
        )

    @Test
    fun `the library pass is text only and covers real games, never app rows`() = runTest {
        coEvery { scrapePreferences.getOptions() } returns ScrapeOptions()
        coEvery { gameDao.getAll() } returns listOf(
            entity(1L, "psp"),
            entity(2L, "android", GameContentType.ANDROID_APP),
        )
        coEvery { metadataRepository.fetchForGame(any(), any(), any(), any(), any(), any()) } returns
            MetadataFetchResult(success = true, source = "screenscraper", message = "ok")

        val result = repo.updateMetadataForAllGames { }

        assertEquals(1, result.total)
        assertEquals(1, result.succeeded)
        coVerify(exactly = 1) {
            metadataRepository.fetchForGame(1L, any(), "psp", any(), match { it.metadataOnly }, any())
        }
        // An app row backs a shortcut's artwork; it has no game metadata to look up.
        coVerify(exactly = 0) { metadataRepository.fetchForGame(2L, any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a scrape pass stops cleanly when the folder is lost after the first game`() = runTest {
        coEvery { scrapePreferences.getOptions() } returns ScrapeOptions()
        coEvery { gameDao.getAll() } returns listOf(entity(1L, "psp"), entity(2L, "psp"), entity(3L, "psp"))
        coEvery { metadataRepository.fetchForGame(1L, any(), any(), any(), any(), any()) } coAnswers {
            folder.value = unavailableFolder   // the grant goes away while game 1 is saving
            MetadataFetchResult(success = true, source = "screenscraper", message = "ok")
        }

        val result = repo.scrapeMissingOnly { }

        assertTrue(result.paused)
        assertEquals(3, result.total)
        coVerify(exactly = 1) { metadataRepository.fetchForGame(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { metadataRepository.fetchForGame(2L, any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { metadataRepository.fetchForGame(3L, any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a metadata-only pass is not gated by the folder`() = runTest {
        folder.value = unavailableFolder
        coEvery { scrapePreferences.getOptions() } returns ScrapeOptions()
        coEvery { gameDao.getAll() } returns listOf(entity(1L, "psp"), entity(2L, "psp"))
        coEvery { metadataRepository.fetchForGame(any(), any(), any(), any(), any(), any()) } returns
            MetadataFetchResult(success = true, source = "screenscraper", message = "ok")

        val result = repo.updateMetadataForPlatform("psp") { }

        assertFalse(result.paused)
        assertEquals(2, result.succeeded)
        coVerify(exactly = 2) { metadataRepository.fetchForGame(any(), any(), any(), any(), any(), any()) }
    }
}
