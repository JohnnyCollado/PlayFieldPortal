package com.playfieldportal.feature.artwork.api

import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.dao.SsMediaCacheDao
import com.playfieldportal.core.data.database.entity.GameEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Browsing ScreenScraper art in the Artwork Studio lists media; it must never rename the game.
 * The live lookup still fills text metadata and the match id, as before — only the title is out
 * of its reach (user decision, 2026-09-29).
 */
class SsMediaCatalogTitleTest {

    private val gameDao = mockk<GameDao>(relaxed = true)
    private val screenScraper = mockk<ScreenScraperApi>(relaxed = true)
    private val cache = mockk<SsMediaCacheDao>(relaxed = true)

    private val catalog = SsMediaCatalog(screenScraper, cache, gameDao, mockk(relaxed = true))

    @Test
    fun `a live lookup from the Studio leaves the game's name alone`() = runTest {
        coEvery { gameDao.getById(1L) } returns GameEntity(
            id = 1L, title = "crash_bandicoot_u", platformId = "psx", romPath = "/roms/crash.bin",
            packageName = null, emulatorPackage = null, artworkUri = null, heroUri = null,
            logoUri = null, description = null, developer = null, publisher = null,
            releaseYear = null, genre = null, steamGridDbId = null,
        )
        coEvery { screenScraper.isEnabled() } returns true
        val info = mockk<SsGameInfo>(relaxed = true) {
            every { ssId } returns 55L
            every { title } returns "Crash Bandicoot"
            every { medias } returns emptyList()
        }
        coEvery { screenScraper.fetchGameInfo(any(), any(), any()) } returns
            SsLookupResult(info = info, diagnostics = mockk(relaxed = true))

        catalog.mediasFor(1L)

        coVerify(exactly = 0) { gameDao.fillScrapedTitleIfMissing(any(), any()) }
        coVerify(exactly = 0) { gameDao.updateScrapedTitle(any(), any()) }
    }
}
