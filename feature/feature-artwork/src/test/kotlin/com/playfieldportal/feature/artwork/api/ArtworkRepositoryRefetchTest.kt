package com.playfieldportal.feature.artwork.api

import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.feature.artwork.MetadataFetchResult
import com.playfieldportal.feature.artwork.MetadataRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fetch Artwork for one game, shared by Game Detail and the XMB game menu. Pins the two things
 * both call sites depend on: eviction stays scoped to this game, and one game never runs two
 * scrapes at once however many surfaces ask.
 */
class ArtworkRepositoryRefetchTest {

    private val gameDao = mockk<GameDao>()
    private val metadataRepository = mockk<MetadataRepository>()
    private val imageCache = mockk<ArtworkImageCache>(relaxed = true)

    private val repo = ArtworkRepository(
        imageCache = imageCache,
        gameDao = gameDao,
        metadataRepository = metadataRepository,
        scrapePreferences = mockk(relaxed = true),
        artworkStore = mockk(relaxed = true),
        internalStore = mockk(relaxed = true),
        ssMediaCacheDao = mockk(relaxed = true),
    )

    private fun entity(id: Long = 1L, artworkUri: String? = null, heroUri: String? = null) = GameEntity(
        id = id, title = "crash", platformId = "psx", romPath = "/roms/psx/crash.bin",
        packageName = null, emulatorPackage = null, artworkUri = artworkUri, heroUri = heroUri, logoUri = null,
        description = null, developer = null, publisher = null, releaseYear = null,
        genre = null, steamGridDbId = null,
    )

    private val found = MetadataFetchResult(success = true, source = "thegamesdb", message = "ok")

    @Test
    fun `evicts this game's old and new refs and nothing else`() = runTest {
        coEvery { gameDao.getById(1L) } returnsMany listOf(
            entity(artworkUri = "file:///art/old_box.png"),
            entity(artworkUri = "file:///art/new_box.png", heroUri = "file:///art/new_hero.png"),
        )
        coEvery { metadataRepository.fetchForGame(any(), any(), any(), any(), any(), any()) } returns found

        val result = repo.refetchArtworkForGame(1L)

        assertTrue(result.success)
        verify {
            imageCache.evict(match<Collection<String>> {
                it.toSet() == setOf("file:///art/old_box.png", "file:///art/new_box.png", "file:///art/new_hero.png")
            })
        }
        // Never the library-wide reset behind Settings > Artwork > Clear All Artwork.
        verify(exactly = 0) { imageCache.clear() }
        coVerify(exactly = 0) { gameDao.clearAllArtworkRefs() }
    }

    @Test
    fun `scrapes with the stored title, platform and rom and forwards asset progress`() = runTest {
        coEvery { gameDao.getById(1L) } returns entity()
        coEvery { metadataRepository.fetchForGame(any(), any(), any(), any(), any(), any()) } answers {
            arg<((String, String) -> Unit)?>(5)?.invoke("TheGamesDB", "Box Art")
            found
        }
        val ticks = mutableListOf<Pair<String, String>>()

        repo.refetchArtworkForGame(1L) { source, asset -> ticks += source to asset }

        coVerify { metadataRepository.fetchForGame(1L, "crash", "psx", "/roms/psx/crash.bin", any(), any()) }
        assertEquals(listOf("TheGamesDB" to "Box Art"), ticks)
    }

    @Test
    fun `a scrape that finds nothing reports the scraper's message`() = runTest {
        coEvery { gameDao.getById(1L) } returns entity()
        coEvery { metadataRepository.fetchForGame(any(), any(), any(), any(), any(), any()) } returns
            MetadataFetchResult(success = false, source = "none", message = "Not found on any source")

        val result = repo.refetchArtworkForGame(1L)

        assertFalse(result.success)
        assertFalse(result.alreadyRunning)
        assertEquals("Not found on any source", result.errorMessage)
    }

    @Test
    fun `an unknown game is reported without scraping`() = runTest {
        coEvery { gameDao.getById(9L) } returns null

        val result = repo.refetchArtworkForGame(9L)

        assertFalse(result.success)
        assertEquals("Game not found", result.errorMessage)
        coVerify(exactly = 0) { metadataRepository.fetchForGame(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a second request for the same game while one runs does not scrape again`() = runTest {
        coEvery { gameDao.getById(1L) } returns entity()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<MetadataFetchResult>()
        coEvery { metadataRepository.fetchForGame(any(), any(), any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            release.await()
        }

        val first = async { repo.refetchArtworkForGame(1L) }
        started.await()
        val second = repo.refetchArtworkForGame(1L)
        release.complete(found)

        assertTrue(second.alreadyRunning)
        assertFalse(second.success)
        assertTrue(first.await().success)
        coVerify(exactly = 1) { metadataRepository.fetchForGame(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a different game is not blocked by one already running`() = runTest {
        coEvery { gameDao.getById(1L) } returns entity(id = 1L)
        coEvery { gameDao.getById(2L) } returns entity(id = 2L)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<MetadataFetchResult>()
        coEvery { metadataRepository.fetchForGame(1L, any(), any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            release.await()
        }
        coEvery { metadataRepository.fetchForGame(2L, any(), any(), any(), any(), any()) } returns found

        val first = async { repo.refetchArtworkForGame(1L) }
        started.await()
        val other = repo.refetchArtworkForGame(2L)
        release.complete(found)
        first.await()

        assertTrue(other.success)
        assertFalse(other.alreadyRunning)
    }

    @Test
    fun `the guard is released when a scrape throws`() = runTest {
        coEvery { gameDao.getById(1L) } returns entity()
        coEvery { metadataRepository.fetchForGame(any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("network down") andThen found

        runCatching { repo.refetchArtworkForGame(1L) }
        val retry = repo.refetchArtworkForGame(1L)

        assertTrue(retry.success)
        assertFalse(retry.alreadyRunning)
    }
}
