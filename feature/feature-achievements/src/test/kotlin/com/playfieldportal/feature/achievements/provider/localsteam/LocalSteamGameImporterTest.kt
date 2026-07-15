package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.AchievementController
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalSteamGameImporterTest {

    private val discovery = mockk<LocalSteamDiscovery>()
    private val gameRepository = mockk<GameRepository>(relaxed = true)
    private val achievements = mockk<AchievementController>(relaxed = true)
    private val importer = LocalSteamGameImporter(discovery, gameRepository, achievements)

    private val marvel = LocalSteamGame(
        folderName = "MARVEL Cosmic Invasion",
        folderDocId = "primary:Games/MARVEL Cosmic Invasion",
        appId = "2753970",
        achievementsUri = null,
    )

    @Test
    fun `a new emu folder becomes a windows game linked to LOCAL_STEAM`() = runTest {
        coEvery { discovery.scan() } returns listOf(marvel)
        coEvery { gameRepository.getByPlatform("windows") } returns emptyList()
        val gameSlot = slot<Game>()
        coEvery { gameRepository.upsert(capture(gameSlot)) } returns 7L

        val result = importer.import()

        assertEquals(EmuGameImportResult(discovered = 1, added = 1), result)
        assertEquals("MARVEL Cosmic Invasion", gameSlot.captured.title)
        assertEquals("windows", gameSlot.captured.platformId)
        assertEquals("/storage/emulated/0/Games/MARVEL Cosmic Invasion", gameSlot.captured.romPath)
        coVerify { achievements.linkManually(7L, AchievementProvider.LOCAL_STEAM, "2753970") }
    }

    @Test
    fun `a re-scan converges on the existing game and just refreshes the link`() = runTest {
        coEvery { discovery.scan() } returns listOf(marvel)
        coEvery { gameRepository.getByPlatform("windows") } returns listOf(
            Game(id = 7L, title = "Marvel Cosmic Invasion!", platformId = "windows"),
        )

        val result = importer.import()

        assertEquals(EmuGameImportResult(discovered = 1, added = 0), result)
        coVerify(exactly = 0) { gameRepository.upsert(any()) }
        coVerify { achievements.linkManually(7L, AchievementProvider.LOCAL_STEAM, "2753970") }
    }

    @Test
    fun `no discovered folders is a quiet no-op`() = runTest {
        coEvery { discovery.scan() } returns emptyList()

        assertEquals(EmuGameImportResult(0, 0), importer.import())
        coVerify(exactly = 0) { gameRepository.getByPlatform(any()) }
    }
}
