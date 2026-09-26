package com.playfieldportal.feature.achievements.provider.localsteam

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.saf.SafChild
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
 * The batch pass over a picked parent folder: register everything identifiable, link what maps onto a
 * library game, hold back what has no achievement list, and DEFER what cannot be identified.
 *
 * The rule with teeth is the last one. A batch run must never write a guessed app id into somebody's
 * game folder, so anything below EXACT/HIGH is counted and left for the per-game flow where a person
 * decides.
 *
 * Robolectric only for a real `Uri` and a real `DocumentsContract`; every collaborator is a mock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LocalSteamBatchMatcherTest {

    private val context = mockk<Context>(relaxed = true)
    private val discovery = mockk<LocalSteamDiscovery>(relaxed = true)
    private val identity = mockk<LocalSteamIdentityResolver>()
    private val importer = mockk<LocalSteamGameImporter>()
    private val achievements = mockk<AchievementController>(relaxed = true)
    private val games = mockk<GameDao>(relaxed = true)

    private val matcher = LocalSteamBatchMatcher(context, discovery, identity, importer, achievements, games)

    private val treeUri: Uri =
        Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AGames")
    private val treeUriText = treeUri.toString()

    @Before
    fun setUp() {
        every { context.contentResolver } returns mockk<ContentResolver>(relaxed = true)
        coEvery { games.getByPlatformOnce("windows") } returns emptyList()
        coEvery { importer.reconcile(any()) } returns EmuGameImportResult(0, 0)
    }

    private fun child(name: String) = SafChild(
        documentId = "primary:Games/$name",
        uri = mockk(relaxed = true),
        name = name,
        mime = DocumentsContract.Document.MIME_TYPE_DIR,
        isDirectory = true,
        lastModified = null,
        sizeBytes = null,
    )

    private fun anchor(name: String, markerAppId: String?) = FolderAnchor(
        treeUri = treeUriText,
        folderName = name,
        folderDocId = "primary:Games/$name",
        settingsDirDocId = markerAppId?.let { "primary:Games/$name/steam_settings" },
        settingsParentDocId = "primary:Games/$name",
        markerAppId = markerAppId,
    )

    private fun folder(name: String, appId: String, hasSchema: Boolean) = LocalSteamGame(
        folderName = name,
        folderDocId = "primary:Games/$name",
        appId = appId,
        achievementsUri = null,
        settingsTreeUri = treeUriText,
        settingsDirDocId = "primary:Games/$name/steam_settings",
        settingsParentDocId = "primary:Games/$name",
        hasSchema = hasSchema,
    )

    @Test
    fun `an empty parent folder says so rather than reporting a failure`() = runTest {
        coEvery { discovery.childFolders(treeUriText, "primary:Games") } returns emptyList()

        val report = matcher.run(treeUri)

        assertEquals(0, report.discovered)
        assertTrue(report.message.contains("CONTAINS your game folders"))
    }

    @Test
    fun `the three piles are partitioned by what each folder actually offers`() = runTest {
        val ready = child("Portal 2")
        val convertible = child("Hades")
        val unknown = child("Some Repack")
        val notSteam = child("Notepad")
        coEvery { discovery.childFolders(treeUriText, "primary:Games") } returns
            listOf(ready, convertible, unknown, notSteam)

        coEvery { discovery.anchor(treeUriText, ready.documentId) } returns anchor("Portal 2", "620")
        coEvery { discovery.anchor(treeUriText, convertible.documentId) } returns anchor("Hades", "1145360")
        coEvery { discovery.anchor(treeUriText, unknown.documentId) } returns anchor("Some Repack", null)
        // No Steam DLL anywhere: not a Steam build, and never counted as unidentified.
        coEvery { discovery.anchor(treeUriText, notSteam.documentId) } returns null

        coEvery { identity.identify(anchor("Portal 2", "620"), null) } returns
            LocalSteamIdentityResolver.Outcome.Resolved("620", AppIdSource.MARKER, written = false)
        coEvery { identity.identify(anchor("Hades", "1145360"), null) } returns
            LocalSteamIdentityResolver.Outcome.Resolved("1145360", AppIdSource.MARKER, written = false)
        coEvery { identity.identify(anchor("Some Repack", null), null) } returns
            LocalSteamIdentityResolver.Outcome.NoMatch

        coEvery { discovery.inspect(treeUriText, "primary:Games/Portal 2") } returns folder("Portal 2", "620", true)
        coEvery { discovery.inspect(treeUriText, "primary:Games/Hades") } returns folder("Hades", "1145360", false)

        val report = matcher.run(treeUri)

        assertEquals(4, report.discovered)
        assertEquals(1, report.needsIdentifying)
        assertEquals(1, report.notSteamBuilds)
        assertEquals(listOf("Hades"), report.convertible.map { it.folderName })
        // Both identifiable folders are registered; only the ready one goes on to the link ladder.
        coVerify { discovery.register(folder("Portal 2", "620", true), AppIdSource.MARKER) }
        coVerify { discovery.register(folder("Hades", "1145360", false), AppIdSource.MARKER) }
    }

    @Test
    fun `a LOW-confidence title is deferred and nothing is written for it`() = runTest {
        val vague = child("Bravely Default")
        coEvery { discovery.childFolders(treeUriText, "primary:Games") } returns listOf(vague)
        coEvery { discovery.anchor(treeUriText, vague.documentId) } returns anchor("Bravely Default", null)
        coEvery { identity.identify(any(), any()) } returns
            LocalSteamIdentityResolver.Outcome.NeedsConfirmation(mockk(relaxed = true), "bravely default")

        val report = matcher.run(treeUri)

        assertEquals(1, report.needsIdentifying)
        assertEquals(0, report.registered)
        coVerify(exactly = 0) { discovery.register(any(), any()) }
        assertTrue(report.message.contains("could not be identified"))
    }

    @Test
    fun `a folder that maps onto a library game links through the mapping ladder and syncs`() = runTest {
        val ready = child("Portal 2")
        coEvery { discovery.childFolders(treeUriText, "primary:Games") } returns listOf(ready)
        coEvery { discovery.anchor(treeUriText, ready.documentId) } returns anchor("Portal 2", "620")
        coEvery { identity.identify(any(), any()) } returns
            LocalSteamIdentityResolver.Outcome.Resolved("620", AppIdSource.MARKER, written = false)
        coEvery { discovery.inspect(any(), any()) } returns folder("Portal 2", "620", true)
        coEvery { importer.reconcile(any()) } returns
            EmuGameImportResult(discovered = 1, linked = 1, linkedGameIds = listOf(7L))

        val report = matcher.run(treeUri)

        assertEquals(1, report.linkedToLibrary)
        assertEquals(0, report.trackedWithoutLibrary)
        // Exactly the games the ladder linked — nothing else is dragged into the sync.
        coVerify(exactly = 1) { achievements.syncGameById(7L) }
    }

    @Test
    fun `a folder with no library game stays tracked and is never synced by id`() = runTest {
        val ready = child("Portal 2")
        coEvery { discovery.childFolders(treeUriText, "primary:Games") } returns listOf(ready)
        coEvery { discovery.anchor(treeUriText, ready.documentId) } returns anchor("Portal 2", "620")
        coEvery { identity.identify(any(), any()) } returns
            LocalSteamIdentityResolver.Outcome.Resolved("620", AppIdSource.MARKER, written = false)
        coEvery { discovery.inspect(any(), any()) } returns folder("Portal 2", "620", true)
        coEvery { importer.reconcile(any()) } returns EmuGameImportResult(discovered = 1, linked = 0)

        val report = matcher.run(treeUri)

        assertEquals(0, report.linkedToLibrary)
        assertEquals(1, report.trackedWithoutLibrary)
        coVerify(exactly = 0) { achievements.syncGameById(any()) }
    }

    @Test
    fun `converted folders are re-read, linked and synced in the same run`() = runTest {
        val preConversion = folder("Hades", "1145360", hasSchema = false)
        val postConversion = folder("Hades", "1145360", hasSchema = true)
        coEvery { discovery.inspect(treeUriText, "primary:Games/Hades") } returns postConversion
        coEvery { importer.reconcile(listOf(postConversion)) } returns
            EmuGameImportResult(discovered = 1, linked = 1, linkedGameIds = listOf(9L))

        val report = matcher.linkAndSync(listOf(preConversion))

        assertEquals(1, report.linkedToLibrary)
        // Re-read matters: the pre-conversion row still says it has no schema and no save location.
        coVerify { importer.reconcile(listOf(postConversion)) }
        coVerify { achievements.syncGameById(9L) }
    }

    @Test
    fun `an unmarked folder is resolved against the library game it maps onto, not a bare name`() = runTest {
        // The folder is named as the user titled the game, so the title ladder joins them — and the
        // resolver is then handed the GAME, whose stored identity and metadata it can use, rather
        // than only a folder name.
        val mapped = child("FINAL FANTASY X X-2 HD Remaster")
        val game = GameEntity(
            id = 4L, title = "ff10", platformId = "windows", romPath = null,
            packageName = null, emulatorPackage = null, artworkUri = null, heroUri = null,
            logoUri = null, description = null, developer = null, publisher = null,
            releaseYear = null, genre = null, steamGridDbId = null,
            userTitleOverride = "FINAL FANTASY X/X-2 HD Remaster",
        )
        coEvery { games.getByPlatformOnce("windows") } returns listOf(game)
        coEvery { discovery.childFolders(treeUriText, "primary:Games") } returns listOf(mapped)
        coEvery { discovery.anchor(treeUriText, mapped.documentId) } returns
            anchor("FINAL FANTASY X X-2 HD Remaster", null)
        coEvery { identity.identify(any(), any()) } returns LocalSteamIdentityResolver.Outcome.NoMatch

        matcher.run(treeUri)

        coVerify { identity.identify(anchor("FINAL FANTASY X X-2 HD Remaster", null), game) }
    }

    @Test
    fun `a folder that maps onto no library game is resolved from the folder name alone`() = runTest {
        val orphan = child("Some Indie Game")
        coEvery { games.getByPlatformOnce("windows") } returns emptyList()
        coEvery { discovery.childFolders(treeUriText, "primary:Games") } returns listOf(orphan)
        coEvery { discovery.anchor(treeUriText, orphan.documentId) } returns anchor("Some Indie Game", null)
        coEvery { identity.identify(any(), any()) } returns LocalSteamIdentityResolver.Outcome.NoMatch

        matcher.run(treeUri)

        // null, not a fabricated game: the resolver decides what to do with only a folder name.
        coVerify { identity.identify(anchor("Some Indie Game", null), null) }
    }
}
