package com.playfieldportal.feature.artwork.api

import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.feature.artwork.MetadataFetchResult
import com.playfieldportal.feature.artwork.MetadataRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Update Metadata from the All Games card: the per-card pass, over everything All Games shows.
 */
class ArtworkRepositoryLibraryPassTest {

    private val gameDao = mockk<GameDao>()
    private val metadataRepository = mockk<MetadataRepository>(relaxed = true)
    private val scrapePreferences = mockk<ArtworkScrapePreferences>()

    private val repo = ArtworkRepository(
        imageCache = mockk(relaxed = true),
        gameDao = gameDao,
        metadataRepository = metadataRepository,
        scrapePreferences = scrapePreferences,
        artworkStore = mockk(relaxed = true),
        internalStore = mockk(relaxed = true),
        ssMediaCacheDao = mockk(relaxed = true),
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
}
