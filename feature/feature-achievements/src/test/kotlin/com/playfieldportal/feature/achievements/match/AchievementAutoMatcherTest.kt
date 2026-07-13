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
    private val matchNoteDao = mockk<com.playfieldportal.core.data.database.dao.AchievementMatchNoteDao>(relaxed = true)
    private val retroApi = mockk<RetroAchievementsApi>()
    private val repository = mockk<AchievementRepository>(relaxed = true)
    private val romReader = mockk<RomBytesReader>()
    private val discOpener = mockk<DiscImageOpener>(relaxed = true)

    private val matcher = AchievementAutoMatcher(gameRepository, linkDao, matchNoteDao, retroApi, repository, romReader, discOpener)

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
    fun `reports a cartridge game with neither a hash nor a title match`() = runTest {
        val g = game(1, "gba")
        stubGames(g)
        coEvery { romReader.read(g) } returns byteArrayOf(9, 9, 9)
        coEvery { retroApi.gameIdForHash(5, any()) } returns null
        coEvery { retroApi.gameIdForTitle(5, g.displayTitle) } returns null

        val report = matcher.matchUnlinked()

        assertEquals(0, report.matched)
        assertEquals(1, report.unmatched.size)
        // Hash computed but not registered, and title also missed — the note names the hash cause.
        assertEquals("ROM hash isn't registered on RetroAchievements, and no title match", report.unmatched.first().reason)
    }

    @Test
    fun `falls back to a title match when the rom hash is not registered`() = runTest {
        val g = game(1, "nds", title = "The World Ends With You")
        stubGames(g)
        coEvery { romReader.read(g) } returns byteArrayOf(1, 2, 3)
        coEvery { retroApi.gameIdForHash(18, any()) } returns null           // wrong regional dump
        coEvery { retroApi.gameIdForTitle(18, "The World Ends With You") } returns "4887"

        val report = matcher.matchUnlinked()

        assertEquals(1, report.matched)
        assertTrue(report.unmatched.isEmpty())
        coVerify { repository.linkManually(1, AchievementProvider.RETRO_ACHIEVEMENTS, "4887") }
    }

    @Test
    fun `title fallback works even when the rom cannot be read`() = runTest {
        val g = game(1, "nds", title = "Chrono Trigger")
        stubGames(g)
        coEvery { romReader.read(g) } returns null                          // unreadable / SAF miss
        coEvery { retroApi.gameIdForTitle(18, "Chrono Trigger") } returns "12345"

        val report = matcher.matchUnlinked()

        assertEquals(1, report.matched)
        coVerify { repository.linkManually(1, AchievementProvider.RETRO_ACHIEVEMENTS, "12345") }
    }

    @Test
    fun `reports unsupported systems without reading the rom`() = runTest {
        val g = game(1, "ps3") // RA has no PS3 achievements — no console id at all
        stubGames(g)

        val report = matcher.matchUnlinked()

        assertEquals(1, report.unmatched.size)
        assertEquals("RetroAchievements has no achievements for this system (ps3)", report.unmatched.first().reason)
        coVerify(exactly = 0) { romReader.read(any()) }
        coVerify(exactly = 0) { discOpener.open(any()) }
    }

    @Test
    fun `disc games hash via the seeking opener, then fall back to title`() = runTest {
        val g = game(1, "ps2", title = "Grand Theft Auto: San Andreas")
        stubGames(g)
        coEvery { discOpener.open(g) } returns null // image unreadable / SAF .cue
        coEvery { retroApi.gameIdForTitle(21, "Grand Theft Auto: San Andreas") } returns "2093"

        val report = matcher.matchUnlinked()

        assertEquals(1, report.matched)
        coVerify { discOpener.open(g) }
        coVerify(exactly = 0) { romReader.read(any()) } // never full-loads a disc image
        coVerify { repository.linkManually(1, AchievementProvider.RETRO_ACHIEVEMENTS, "2093") }
    }

    @Test
    fun `records a persisted note naming why each unmatched game failed`() = runTest {
        val g = game(1, "wii", title = "Wheelie Breakers") // RA console, but no disc hasher for Wii
        stubGames(g)
        coEvery { retroApi.gameIdForTitle(19, any()) } returns null

        matcher.matchUnlinked()

        coVerify { matchNoteDao.clear() } // rewritten from scratch each run
        coVerify {
            matchNoteDao.upsert(
                match { it.gameId == 1L && it.reason.contains("isn't supported yet") },
            )
        }
    }

    @Test
    fun `matches steam pc games by title`() = runTest {
        val g = game(1, "windows", title = "Half-Life 2")
        stubGames(g)
        coEvery { repository.resolveSteamLink(1, "Half-Life 2") } returns "220"

        val report = matcher.matchUnlinked()

        assertEquals(1, report.matched)
    }

    @Test
    fun `links a steam game by the appid embedded in its shortcut, skipping the title guess`() = runTest {
        val g = Game(
            id = 1, title = "The Adventures of Elliot", platformId = "windows",
            launchIntentUri = "intent:#Intent;action=app.gamenative.LAUNCH_GAME;i.app_id=3483510;S.game_source=STEAM;end",
        )
        stubGames(g)

        val report = matcher.matchUnlinked()

        assertEquals(1, report.matched)
        coVerify { repository.linkManually(1, AchievementProvider.STEAM, "3483510") }
        coVerify(exactly = 0) { repository.resolveSteamLink(any(), any()) }
    }
}
