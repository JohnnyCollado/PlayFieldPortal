package com.playfieldportal.feature.achievements.provider.steam

import com.playfieldportal.core.data.database.entity.LocalSteamFolderEntity
import com.playfieldportal.core.data.database.entity.ProviderGameLinkEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.WindowsGameLabel
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.match.AchievementAutoMatcher
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamDiscovery
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamFolderLinker
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamGame
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A label change stores the label first, then removes only the links that contradict it. A LOCAL_STEAM
 * link survives every change — the folder the user picked is never thrown away.
 */
class WindowsGameLabelerTest {

    private val games = mockk<GameRepository>(relaxed = true)
    private val achievements = mockk<AchievementController>(relaxed = true)
    private val matcher = mockk<AchievementAutoMatcher>()
    private val folderLinker = mockk<LocalSteamFolderLinker>()
    private val discovery = mockk<LocalSteamDiscovery>()
    private val labeler = WindowsGameLabeler(games, achievements, matcher, folderLinker, discovery)

    private fun link(provider: AchievementProvider, appId: String = "220", gameId: Long = 1L) =
        ProviderGameLinkEntity(gameId, provider.name, appId, "MANUAL", 0L)

    private fun linksAre(vararg links: ProviderGameLinkEntity) {
        coEvery { achievements.observeLinks(1L) } returns flowOf(links.toList())
    }

    @Test
    fun `the label is stored before any link changes`() = runTest {
        linksAre(link(AchievementProvider.STEAM))
        coEvery { folderLinker.registeredFolderFor(1L) } returns null

        labeler.setLabel(1L, WindowsGameLabel.LOCAL)

        coVerifyOrder {
            games.setWindowsLabel(1L, WindowsGameLabel.LOCAL)
            achievements.unlinkProvider(1L, AchievementProvider.STEAM)
        }
    }

    @Test
    fun `labelling Local removes the Steam link and keeps the folder link`() = runTest {
        linksAre(link(AchievementProvider.STEAM), link(AchievementProvider.LOCAL_STEAM))

        val outcome = labeler.setLabel(1L, WindowsGameLabel.LOCAL)

        assertEquals(WindowsGameLabeler.LabelOutcome.Saved, outcome)
        coVerify { achievements.unlinkProvider(1L, AchievementProvider.STEAM) }
        coVerify(exactly = 0) { achievements.unlinkProvider(1L, AchievementProvider.LOCAL_STEAM) }
        coVerify(exactly = 0) { achievements.unlink(any()) }
        coVerify(exactly = 0) { folderLinker.registeredFolderFor(any()) }
    }

    @Test
    fun `labelling Local links a folder the registry already holds`() = runTest {
        val folder = mockk<LocalSteamGame>()
        linksAre(link(AchievementProvider.STEAM))
        coEvery { folderLinker.registeredFolderFor(1L) } returns folder
        coEvery { folderLinker.linkRegistered(1L, folder) } returns
            LocalSteamFolderLinker.LinkOutcome.Linked("220", "Half-Life 2")
        coEvery { achievements.syncGameById(1L) } returns ProviderSyncResult.NotLinked

        val outcome = labeler.setLabel(1L, WindowsGameLabel.LOCAL)

        assertEquals(WindowsGameLabeler.LabelOutcome.FolderLinked("Half-Life 2"), outcome)
        coVerify { folderLinker.linkRegistered(1L, folder) }
        coVerify { achievements.syncGameById(1L) }
    }

    @Test
    fun `labelling Non-Steam removes the Steam link and keeps the folder link`() = runTest {
        linksAre(link(AchievementProvider.STEAM), link(AchievementProvider.LOCAL_STEAM))

        val outcome = labeler.setLabel(1L, WindowsGameLabel.NON_STEAM)

        assertEquals(WindowsGameLabeler.LabelOutcome.Saved, outcome)
        coVerify { achievements.unlinkProvider(1L, AchievementProvider.STEAM) }
        coVerify(exactly = 0) { achievements.unlinkProvider(1L, AchievementProvider.LOCAL_STEAM) }
        coVerify(exactly = 0) { achievements.unlink(any()) }
        coVerify(exactly = 0) { matcher.matchSingleAsSteam(any()) }
    }

    @Test
    fun `labelling Steam links the game to Steam when it has no Steam link`() = runTest {
        linksAre(link(AchievementProvider.LOCAL_STEAM))
        coEvery { matcher.matchSingleAsSteam(1L) } returns true
        coEvery { achievements.syncGameById(1L) } returns ProviderSyncResult.NotLinked

        val outcome = labeler.setLabel(1L, WindowsGameLabel.STEAM)

        assertEquals(WindowsGameLabeler.LabelOutcome.SteamLinked, outcome)
        coVerifyOrder {
            games.setWindowsLabel(1L, WindowsGameLabel.STEAM)
            matcher.matchSingleAsSteam(1L)
            achievements.syncGameById(1L)
        }
    }

    @Test
    fun `labelling Steam reports when Steam has no match`() = runTest {
        linksAre()
        coEvery { matcher.matchSingleAsSteam(1L) } returns false

        val outcome = labeler.setLabel(1L, WindowsGameLabel.STEAM)

        assertEquals(WindowsGameLabeler.LabelOutcome.SteamNotFound, outcome)
        coVerify(exactly = 0) { achievements.syncGameById(any()) }
    }

    @Test
    fun `labelling Steam keeps an existing Steam link and does not rematch`() = runTest {
        linksAre(link(AchievementProvider.STEAM))

        val outcome = labeler.setLabel(1L, WindowsGameLabel.STEAM)

        assertEquals(WindowsGameLabeler.LabelOutcome.Saved, outcome)
        coVerify(exactly = 0) { matcher.matchSingleAsSteam(any()) }
        coVerify(exactly = 0) { achievements.unlinkProvider(any(), any()) }
    }

    @Test
    fun `returning to Auto changes no links`() = runTest {
        val outcome = labeler.setLabel(1L, null)

        assertEquals(WindowsGameLabeler.LabelOutcome.Saved, outcome)
        coVerify { games.setWindowsLabel(1L, null) }
        coVerify(exactly = 0) { achievements.unlinkProvider(any(), any()) }
        coVerify(exactly = 0) { achievements.unlink(any()) }
        coVerify(exactly = 0) { matcher.matchSingleAsSteam(any()) }
        coVerify(exactly = 0) { achievements.linkManually(any(), any(), any()) }
    }

    @Test
    fun `linked folders join links to the registry by app id`() = runTest {
        coEvery { games.getByPlatform("windows") } returns listOf(game(1L), game(2L), game(3L))
        coEvery { achievements.linksByProvider(AchievementProvider.LOCAL_STEAM) } returns listOf(
            link(AchievementProvider.LOCAL_STEAM, appId = "220", gameId = 1L),
            link(AchievementProvider.LOCAL_STEAM, appId = "400", gameId = 2L),
            // A non-Windows game's link is not the Game List's business.
            link(AchievementProvider.LOCAL_STEAM, appId = "500", gameId = 99L),
        )
        coEvery { discovery.registeredFolders() } returns listOf(
            LocalSteamFolderEntity(appId = "220", folderName = "Half-Life 2", treeUri = "tree", folderDocId = "primary:Games/HL2",
                settingsParentDocId = "primary:Games/HL2", appIdSource = "MARKER", lastSeenAt = 0L,
            ),
        )

        val folders = labeler.linkedFolders()

        assertEquals(
            mapOf(
                1L to WindowsGameLabeler.LinkedFolder("220", "Half-Life 2", "primary:Games/HL2"),
                // Linked, but its registry row is gone: the link still shows, without a path.
                2L to WindowsGameLabeler.LinkedFolder("400", null, null),
            ),
            folders,
        )
    }

    private fun game(id: Long) = mockk<Game>().also { every { it.id } returns id }
}
