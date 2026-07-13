package com.playfieldportal.feature.achievements.match

import com.playfieldportal.core.data.database.dao.ProviderGameLinkDao
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.AchievementRepository
import com.playfieldportal.feature.achievements.api.RetroAchievementsApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AchievementAutoMatcherTest {

    private val gameRepository = mockk<GameRepository>()
    private val linkDao = mockk<ProviderGameLinkDao>(relaxed = true)
    private val retroApi = mockk<RetroAchievementsApi>()
    private val repository = mockk<AchievementRepository>(relaxed = true)
    private val romReader = mockk<RomBytesReader>()

    private val matcher = AchievementAutoMatcher(gameRepository, linkDao, retroApi, repository, romReader)

    private fun game(id: Long, platform: String, title: String = "Game $id") =
        Game(id = id, title = title, platformId = platform)

    private fun stubGames(vararg games: Game) {
        every { gameRepository.observeGamesOnly() } returns flowOf(games.toList())
        games.forEach { coEvery { linkDao.getForGame(it.id) } returns null }
    }

    @Test
    fun `links a cartridge game by its rom hash`() = runTest {
        val g = game(1, "snes")
        stubGames(g)
        coEvery { romReader.read(g) } returns byteArrayOf(1, 2, 3, 4)
        coEvery { retroApi.gameIdForHash(3, any()) } returns "999"

        val report = matcher.matchUnlinked()

        assertEquals(1, report.matched)
        assertTrue(report.unmatched.isEmpty())
        coVerify { repository.linkManually(1, AchievementProvider.RETRO_ACHIEVEMENTS, "999") }
    }

    @Test
    fun `reports a cartridge game with no hash match`() = runTest {
        val g = game(1, "gba")
        stubGames(g)
        coEvery { romReader.read(g) } returns byteArrayOf(9, 9, 9)
        coEvery { retroApi.gameIdForHash(5, any()) } returns null

        val report = matcher.matchUnlinked()

        assertEquals(0, report.matched)
        assertEquals(1, report.unmatched.size)
        assertEquals("no RetroAchievements match", report.unmatched.first().reason)
    }

    @Test
    fun `reports unsupported systems without reading the rom`() = runTest {
        val g = game(1, "psx")
        stubGames(g)

        val report = matcher.matchUnlinked()

        assertEquals(1, report.unmatched.size)
        assertEquals("unsupported system", report.unmatched.first().reason)
        coVerify(exactly = 0) { romReader.read(any()) }
    }

    @Test
    fun `matches steam pc games by title`() = runTest {
        val g = game(1, "windows", title = "Half-Life 2")
        stubGames(g)
        coEvery { repository.resolveSteamLink(1, "Half-Life 2") } returns "220"

        val report = matcher.matchUnlinked()

        assertEquals(1, report.matched)
    }
}
