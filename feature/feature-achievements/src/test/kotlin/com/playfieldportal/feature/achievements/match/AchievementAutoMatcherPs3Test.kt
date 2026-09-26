package com.playfieldportal.feature.achievements.match

import com.playfieldportal.core.data.database.dao.ProviderGameLinkDao
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.achievements.provider.ps3.Ps3TropDirReader
import com.playfieldportal.feature.achievements.provider.ps3.Ps3Trophy
import com.playfieldportal.feature.achievements.provider.ps3.Ps3TrophyDiscovery
import com.playfieldportal.feature.achievements.provider.ps3.Ps3TrophySet
import com.playfieldportal.feature.achievements.provider.retro.RaHashResolver
import com.playfieldportal.feature.achievements.provider.vita.TropUsrParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The PS3 branch: link by the id the disc declares, surface each failure reason distinctly, and
 * fall back to a title match only when the image can't be read — and only on a strong match.
 */
class AchievementAutoMatcherPs3Test {

    private val gameRepository = mockk<GameRepository>()
    private val linkDao = mockk<ProviderGameLinkDao>(relaxed = true)
    private val matchNoteDao = mockk<com.playfieldportal.core.data.database.dao.AchievementMatchNoteDao>(relaxed = true)
    private val repository = mockk<AchievementController>(relaxed = true)
    private val ps3TropDirReader = mockk<Ps3TropDirReader>()
    private val ps3TrophyDiscovery = mockk<Ps3TrophyDiscovery> {
        coEvery { availableSetIds() } returns emptyList()
    }

    private val matcher = AchievementAutoMatcher(
        gameRepository, linkDao, matchNoteDao, mockk<RaHashResolver>(), repository,
        mockk<RomBytesReader>(), mockk<DiscImageOpener>(relaxed = true),
        mockk<com.playfieldportal.feature.artwork.api.SteamGridDbApi>(relaxed = true),
        mockk<com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamDiscovery> {
            coEvery { scan() } returns emptyList()
        },
        mockk<com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamOwnership>(relaxed = true),
        mockk<com.playfieldportal.feature.achievements.provider.steam.SteamAppListResolver> {
            coEvery { officialNameOf(any()) } returns null
        },
        ps3TropDirReader, ps3TrophyDiscovery,
    )

    private fun ps3Game(title: String = "Witch and the Hundred Knight, The") =
        Game(id = 7, title = title, platformId = "ps3", romPath = "/roms/ps3/witch.iso")

    private fun stub(game: Game) {
        every { gameRepository.observeGamesOnly() } returns flowOf(listOf(game))
        coEvery { linkDao.getForGame(game.id) } returns null
        coEvery { gameRepository.getById(game.id) } returns game
    }

    private fun set(npCommId: String, titleName: String) = Ps3TrophySet(
        npCommId = npCommId,
        titleName = titleName,
        trophies = listOf(
            Ps3Trophy(
                coinId = "$npCommId:0", npCommId = npCommId, id = 0, name = "Grand Finale",
                detail = "", grade = TropUsrParser.Grade.PLATINUM, hidden = false,
                unlocked = false, unlockedAtEpochMillis = null, iconUri = null,
            ),
        ),
    )

    @Test
    fun `links the base set - the first id TROPDIR lists`() = runTest {
        val g = ps3Game()
        stub(g)
        coEvery { ps3TropDirReader.npCommIdsFor(g) } returns Ps3TropDirReader.Result.Found(
            npCommIds = listOf("NPWR05915_00", "NPWR05915_01"),
            titleId = "BLUS30964",
            title = "The Witch and the Hundred Knight",
        )

        val report = matcher.matchUnlinked()

        assertEquals(1, report.matched)
        assertTrue(report.unmatched.isEmpty())
        // The base set is the link's stable identity; the subset is merged at fetch time, not stored.
        coVerify { repository.linkManually(7, AchievementProvider.PS3_TROPHY, "NPWR05915_00") }
    }

    @Test
    fun `a never-booted game is linked anyway rather than reported as having no trophies`() = runTest {
        val g = ps3Game()
        stub(g)
        coEvery { ps3TropDirReader.npCommIdsFor(g) } returns
            Ps3TropDirReader.Result.Found(listOf("NPWR05915_00"), "BLUS30964", "Game")
        // Nothing under the grant yet — the emulator creates the folder on first registration.
        coEvery { ps3TrophyDiscovery.availableSetIds() } returns emptyList()

        val report = matcher.matchUnlinked()

        assertEquals(1, report.matched)
        assertTrue(report.unmatched.isEmpty())
    }

    @Test
    fun `each disc-side failure reason surfaces distinctly`() = runTest {
        val cases = mapOf(
            Ps3TropDirReader.Result.NoTropDir to "declares no trophies",
            Ps3TropDirReader.Result.NoTrophySets to "lists no trophy set",
            Ps3TropDirReader.Result.NoPs3Game to "no PS3_GAME folder",
            Ps3TropDirReader.Result.NoImage to "No PS3 image on this device",
        )
        for ((result, expected) in cases) {
            val g = ps3Game()
            stub(g)
            coEvery { ps3TropDirReader.npCommIdsFor(g) } returns result

            val report = matcher.matchUnlinked()

            assertEquals(0, report.matched, "expected no link for $result")
            assertEquals(1, report.unmatched.size)
            assertTrue(
                report.unmatched.single().reason.contains(expected),
                "reason for $result was \"${report.unmatched.single().reason}\"",
            )
        }
    }

    @Test
    fun `an unreadable image falls back to the trophy set's own title, article swap included`() = runTest {
        val g = ps3Game(title = "Witch and the Hundred Knight, The")
        stub(g)
        coEvery { ps3TropDirReader.npCommIdsFor(g) } returns Ps3TropDirReader.Result.Unreadable
        coEvery { ps3TrophyDiscovery.availableSetIds() } returns listOf("NPWR05915_00")
        coEvery { ps3TrophyDiscovery.loadOneSet("NPWR05915_00") } returns
            set("NPWR05915_00", "The Witch and the Hundred Knight")

        val report = matcher.matchUnlinked()

        assertEquals(1, report.matched)
        coVerify { repository.linkManually(7, AchievementProvider.PS3_TROPHY, "NPWR05915_00") }
    }

    @Test
    fun `the title fallback never links a set that only loosely resembles the game`() = runTest {
        val g = ps3Game(title = "Witch and the Hundred Knight, The")
        stub(g)
        coEvery { ps3TropDirReader.npCommIdsFor(g) } returns Ps3TropDirReader.Result.Unreadable
        coEvery { ps3TrophyDiscovery.availableSetIds() } returns listOf("NPWR09999_00")
        coEvery { ps3TrophyDiscovery.loadOneSet("NPWR09999_00") } returns
            set("NPWR09999_00", "The Witch and the Hundred Knight 2 Revival")

        val report = matcher.matchUnlinked()

        assertEquals(0, report.matched)
        assertTrue(report.unmatched.single().reason.contains("no installed trophy set matches"))
        coVerify(exactly = 0) { repository.linkManually(any(), AchievementProvider.PS3_TROPHY, any()) }
    }

    @Test
    fun `an unreadable image with no grant says to set the PS3 data folder`() = runTest {
        val g = ps3Game()
        stub(g)
        coEvery { ps3TropDirReader.npCommIdsFor(g) } returns Ps3TropDirReader.Result.Unreadable
        coEvery { ps3TrophyDiscovery.availableSetIds() } returns emptyList()

        val report = matcher.matchUnlinked()

        assertEquals(0, report.matched)
        assertTrue(report.unmatched.single().reason.contains("set your PS3 Data Folder"))
    }

    @Test
    fun `the per-game match reports the same reason the batch run would`() = runTest {
        val g = ps3Game()
        stub(g)
        coEvery { ps3TropDirReader.npCommIdsFor(g) } returns Ps3TropDirReader.Result.NoTropDir

        val result = matcher.matchSingleAsPs3(g.id)

        assertTrue(result is AchievementAutoMatcher.Ps3MatchResult.Unmatched)
        assertTrue(result.reason.contains("declares no trophies"))
    }
}
