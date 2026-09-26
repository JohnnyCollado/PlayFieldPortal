package com.playfieldportal.feature.achievements.provider.localsteam

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.LocalCopyOwnership
import com.playfieldportal.feature.achievements.AchievementController
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Linking one picked game folder: register, link LOCAL_STEAM, classify ownership — and stop for the
 * user whenever the folder is not ready to track.
 *
 * Robolectric only so that `Uri` and `DocumentsContract.getTreeDocumentId` are real — a picked
 * folder's tree document id IS that folder, and that is the one piece of SAF behaviour the linker
 * depends on. Every collaborator is a mock: what is being pinned is the ORDER of the steps and which
 * of them are refused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LocalSteamFolderLinkerTest {

    private val context = mockk<Context>(relaxed = true)
    private val discovery = mockk<LocalSteamDiscovery>(relaxed = true)
    private val identity = mockk<LocalSteamIdentityResolver>()
    private val generator = mockk<LocalSteamSchemaGenerator>()
    private val achievements = mockk<AchievementController>(relaxed = true)
    private val ownership = mockk<LocalSteamOwnership>(relaxed = true)
    private val games = mockk<GameDao>(relaxed = true)
    private val credentials = mockk<AchievementCredentialsProvider>()

    private val linker = LocalSteamFolderLinker(
        context, discovery, identity, generator, achievements, ownership, games, credentials,
    )

    // A tree uri whose tree document id is the game folder — which is what OpenDocumentTree on a
    // game folder actually hands back.
    private val treeUri: Uri =
        Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AGames%2FPortal%202")
    private val treeUriText = treeUri.toString()

    private val game = GameEntity(
        id = 7L, title = "Portal 2", platformId = "windows", romPath = null,
        packageName = null, emulatorPackage = null, artworkUri = null, heroUri = null,
        logoUri = null, description = null, developer = null, publisher = null,
        releaseYear = null, genre = null, steamGridDbId = null,
    )

    private val anchor = FolderAnchor(
        treeUri = treeUriText,
        folderName = "Portal 2",
        folderDocId = "primary:Games/Portal 2",
        settingsDirDocId = "primary:Games/Portal 2/steam_settings",
        settingsParentDocId = "primary:Games/Portal 2",
        markerAppId = "620",
    )

    private fun folder(hasSchema: Boolean = true) = LocalSteamGame(
        folderName = "Portal 2",
        folderDocId = "primary:Games/Portal 2",
        appId = "620",
        achievementsUri = null,
        settingsTreeUri = treeUriText,
        settingsDirDocId = "primary:Games/Portal 2/steam_settings",
        settingsParentDocId = "primary:Games/Portal 2",
        hasSchema = hasSchema,
    )

    @Before
    fun setUp() {
        every { context.contentResolver } returns mockk<ContentResolver>(relaxed = true)
        coEvery { games.getById(7L) } returns game
        coEvery { discovery.anchor(any(), any()) } returns anchor
        coEvery { credentials.goldbergInstallerEnabled() } returns true
    }

    @Test
    fun `a ready folder registers, links and classifies ownership`() = runTest {
        coEvery { identity.identify(anchor, game) } returns
            LocalSteamIdentityResolver.Outcome.Resolved("620", AppIdSource.MARKER, written = false)
        coEvery { discovery.inspect(any(), any()) } returns folder()
        coEvery { ownership.classify(7L, "620") } returns LocalCopyOwnership.NOT_IN_LIBRARY

        val outcome = linker.link(7L, treeUri)

        assertEquals(LocalSteamFolderLinker.LinkOutcome.Linked("620", "Portal 2"), outcome)
        coVerify { discovery.register(folder(), AppIdSource.MARKER) }
        coVerify { achievements.linkManually(7L, AchievementProvider.LOCAL_STEAM, "620") }
        // Not owned on Steam, so there is no second link to make.
        coVerify(exactly = 0) { achievements.linkManually(7L, AchievementProvider.STEAM, any()) }
    }

    @Test
    fun `an owned copy keeps the double link to STEAM`() = runTest {
        coEvery { identity.identify(anchor, game) } returns
            LocalSteamIdentityResolver.Outcome.Resolved("620", AppIdSource.MARKER, written = false)
        coEvery { discovery.inspect(any(), any()) } returns folder()
        coEvery { ownership.classify(7L, "620") } returns LocalCopyOwnership.OWNED

        linker.link(7L, treeUri)

        coVerify { achievements.linkManually(7L, AchievementProvider.LOCAL_STEAM, "620") }
        coVerify { achievements.linkManually(7L, AchievementProvider.STEAM, "620") }
    }

    @Test
    fun `a folder with no achievement list stops at NeedsKit and links nothing yet`() = runTest {
        coEvery { identity.identify(anchor, game) } returns
            LocalSteamIdentityResolver.Outcome.Resolved("620", AppIdSource.TITLE_MATCH, written = true)
        coEvery { discovery.inspect(any(), any()) } returns folder(hasSchema = false)

        val outcome = linker.link(7L, treeUri)

        assertEquals(
            LocalSteamFolderLinker.LinkOutcome.NeedsKit("620", "Portal 2", installerEnabled = true, source = AppIdSource.TITLE_MATCH),
            outcome,
        )
        // Nothing is linked or registered until a person chooses Install & Link or Link Only.
        coVerify(exactly = 0) { achievements.linkManually(any(), any(), any()) }
        coVerify(exactly = 0) { discovery.register(any(), any()) }
    }

    @Test
    fun `NeedsKit reports the installer as off so the prompt can point at the setting`() = runTest {
        coEvery { credentials.goldbergInstallerEnabled() } returns false
        coEvery { identity.identify(anchor, game) } returns
            LocalSteamIdentityResolver.Outcome.Resolved("620", AppIdSource.MARKER, written = false)
        coEvery { discovery.inspect(any(), any()) } returns folder(hasSchema = false)

        val outcome = linker.link(7L, treeUri)

        assertEquals(false, (outcome as LocalSteamFolderLinker.LinkOutcome.NeedsKit).installerEnabled)
    }

    @Test
    fun `Install and Link is refused outright while the installer opt-in is off`() = runTest {
        coEvery { credentials.goldbergInstallerEnabled() } returns false

        val outcome = linker.installKitAndLink(7L, treeUri, "620", AppIdSource.MARKER)

        assertTrue(outcome is LocalSteamFolderLinker.LinkOutcome.Failed)
        // The refusal is complete: the generator is never even asked.
        coVerify(exactly = 0) { generator.generate(any()) }
    }

    @Test
    fun `Link Only links at 0 percent and writes nothing further into the folder`() = runTest {
        coEvery { discovery.inspect(any(), any()) } returns folder(hasSchema = false)
        coEvery { ownership.classify(7L, "620") } returns null

        val outcome = linker.linkWithoutKit(7L, treeUri, "620", AppIdSource.TITLE_MATCH)

        assertEquals(LocalSteamFolderLinker.LinkOutcome.Linked("620", "Portal 2"), outcome)
        coVerify { discovery.register(any(), AppIdSource.TITLE_MATCH) }
        coVerify { achievements.linkManually(7L, AchievementProvider.LOCAL_STEAM, "620") }
        coVerify(exactly = 0) { generator.generate(any()) }
    }

    @Test
    fun `a folder with no Steam DLL is a terminal NoEmuData naming the real cause`() = runTest {
        coEvery { discovery.anchor(any(), any()) } returns null

        val outcome = linker.link(7L, treeUri)

        assertTrue(outcome is LocalSteamFolderLinker.LinkOutcome.NoEmuData)
        assertTrue(outcome.reason.contains("not a Steam"))
    }

    @Test
    fun `an ambiguous title becomes NeedsConfirmation carrying the query PFP actually searched`() = runTest {
        val result = mockk<com.playfieldportal.feature.artwork.match.StorefrontMatchResult>()
        coEvery { identity.identify(anchor, game) } returns
            LocalSteamIdentityResolver.Outcome.NeedsConfirmation(result, "portal 2")

        val outcome = linker.link(7L, treeUri)

        assertEquals(
            LocalSteamFolderLinker.LinkOutcome.NeedsConfirmation(result, "portal 2", "Portal 2"),
            outcome,
        )
    }

    @Test
    fun `a grant revoked mid-flow fails honestly instead of linking a folder it cannot read`() = runTest {
        coEvery { identity.identify(anchor, game) } returns
            LocalSteamIdentityResolver.Outcome.Resolved("620", AppIdSource.MARKER, written = false)
        // The marker was just read, so a null read-back means the folder went away underneath us.
        coEvery { discovery.inspect(any(), any()) } returns null

        val outcome = linker.link(7L, treeUri)

        assertTrue(outcome is LocalSteamFolderLinker.LinkOutcome.Failed)
        coVerify(exactly = 0) { achievements.linkManually(any(), any(), any()) }
    }

    @Test
    fun `an unreachable store never links and never writes`() = runTest {
        coEvery { identity.identify(anchor, game) } returns
            LocalSteamIdentityResolver.Outcome.Unavailable("Steam could not be reached")

        val outcome = linker.link(7L, treeUri)

        assertTrue(outcome is LocalSteamFolderLinker.LinkOutcome.NoEmuData)
        coVerify(exactly = 0) { achievements.linkManually(any(), any(), any()) }
        coVerify(exactly = 0) { discovery.register(any(), any()) }
    }

    @Test
    fun `the registry pre-check finds the folder a batch match already registered`() = runTest {
        coEvery { games.getById(7L) } returns game
        coEvery { discovery.registeredFolders() } returns listOf(
            com.playfieldportal.core.data.database.entity.LocalSteamFolderEntity(
                appId = "620",
                folderName = "Portal 2",
                treeUri = treeUriText,
                folderDocId = "primary:Games/Portal 2",
                settingsParentDocId = "primary:Games/Portal 2",
                appIdSource = "MARKER",
                lastSeenAt = 1_700_000_000_000L,
            )
        )
        coEvery { discovery.findByAppId("620") } returns folder()

        assertEquals(folder(), linker.registeredFolderFor(7L))
    }

    @Test
    fun `the pre-check finds nothing when no registered folder matches this game`() = runTest {
        coEvery { discovery.registeredFolders() } returns emptyList()

        assertEquals(null, linker.registeredFolderFor(7L))
    }
}
