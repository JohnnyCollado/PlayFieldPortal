package com.playfieldportal.feature.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.data.repository.WindowsLibrarySetup
import com.playfieldportal.core.data.repository.WindowsSetupState
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.GameCollection
import com.playfieldportal.core.domain.repository.GameRepository
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shared shortcut-import funnel (docs/windows-library-refactor-plan.md section 3): entity-
 * first writes, three-shape dedupe, certain-appid STEAM linking, and the setup-prompt flag.
 */
@RunWith(RobolectricTestRunner::class)
class PcShortcutImporterTest {

    private val gameRepository = mockk<GameRepository>()
    private val memoryCards = mockk<MemoryCardRepository>(relaxed = true)
    private val windowsLibrary = mockk<WindowsLibrarySetup>(relaxed = true)
    private val linker = mockk<PcGameAchievementLinker>(relaxed = true)
    private val collections = mockk<CollectionRepository>(relaxed = true)

    // The ledger is real: it is DataStore-backed and what the reconcile sweep reads.
    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val ledger = PcShortcutLedger(appContext)

    @Before
    fun clearLedger() {
        runBlocking { appContext.pfpDataStore.edit { it.clear() } }
    }

    // Choices are real for the same reason: DataStore-backed, cleared with the ledger.
    private val choices = LauncherChoiceRepository(appContext)

    private fun importer(context: Context = mockk(relaxed = true)) = PcShortcutImporter(
        context, gameRepository, memoryCards, windowsLibrary, linker, ledger, choices, collections,
    )

    private fun ready() {
        coEvery { windowsLibrary.ensure() } returns WindowsSetupState.Ready("/storage/emulated/0/Roms/windows")
        // Storefront capture (C16 task 0.5) rides every import path that has a store id.
        coEvery { gameRepository.updateStorefrontIdentity(any(), any(), any()) } returns Unit
    }

    private fun windowsGame(id: Long, title: String) = Game(
        id = id, title = title, platformId = "windows",
        isManualEntry = true, contentType = GameContentType.GAME,
    )

    // ── Pins ──────────────────────────────────────────────────────────────────

    @Test
    fun `gamenative pin creates the game and links steam from the shortcut id`() = runTest {
        ready()
        coEvery { gameRepository.getLauncherShortcut("app.gamenative", "game_1984270") } returns null
        coEvery { gameRepository.getByPlatform("windows") } returns emptyList()
        val stored = slot<Game>()
        coEvery { gameRepository.upsert(capture(stored)) } returns 42L

        val result = importer().importPinnedShortcut("app.gamenative", "game_1984270", "Digimon Story")

        assertEquals(42L, result.gameId)
        assertTrue(result.added)
        assertFalse(result.needsSetup)
        assertEquals("windows", stored.captured.platformId)
        assertEquals("game_1984270", stored.captured.shortcutId)
        coVerify { linker.linkSteam(42L, "1984270") }
        coVerify { gameRepository.updateStorefrontIdentity(42L, "STEAM", "1984270") }
    }

    @Test
    fun `re-pinning the same shortcut adds nothing`() = runTest {
        ready()
        coEvery { gameRepository.getLauncherShortcut("com.xiaoji.egggame", "NieR:Automata™") } returns
            windowsGame(7L, "NieR:Automata™").copy(packageName = "com.xiaoji.egggame", shortcutId = "NieR:Automata™")

        val result = importer().importPinnedShortcut("com.xiaoji.egggame", "NieR:Automata™", "NieR:Automata™")

        assertEquals(7L, result.gameId)
        assertFalse(result.added)
        coVerify(exactly = 0) { gameRepository.upsert(any()) }
    }

    @Test
    fun `a pin merges into the folder-imported game by normalized title, attaching its handle`() = runTest {
        ready()
        // Folder import created "MARVEL Cosmic Invasion"; the Ludashi pin arrives squashed.
        val folderGame = windowsGame(9L, "MARVEL Cosmic Invasion")
        coEvery { gameRepository.getLauncherShortcut("com.ludashi.aibench", "MARVELCosmicInvasion") } returns null
        coEvery { gameRepository.getByPlatform("windows") } returns listOf(folderGame)
        val updated = slot<Game>()
        coEvery { gameRepository.upsert(capture(updated)) } returns 9L

        val result = importer().importPinnedShortcut("com.ludashi.aibench", "MARVELCosmicInvasion", "MARVELCosmicInvasion")

        assertEquals(9L, result.gameId)
        assertFalse(result.added)
        assertEquals(9L, updated.captured.id)
        assertEquals("MARVELCosmicInvasion", updated.captured.shortcutId)
        assertEquals("com.ludashi.aibench", updated.captured.packageName)
        // The Ludashi shortcut id carries no appid — no STEAM link may be invented.
        coVerify(exactly = 0) { linker.linkSteam(any(), any()) }
    }

    @Test
    fun `missing library setup flags the one-time prompt but never loses the game`() = runTest {
        coEvery { windowsLibrary.ensure() } returns WindowsSetupState.NoRomRoot
        coEvery { gameRepository.getLauncherShortcut(any(), any()) } returns null
        coEvery { gameRepository.getByPlatform("windows") } returns emptyList()
        coEvery { gameRepository.upsert(any()) } returns 5L

        val result = importer().importPinnedShortcut("com.winlator", "shortcut-1", "Some Game")

        assertEquals(5L, result.gameId)
        assertTrue(result.needsSetup)
        coVerify { windowsLibrary.flagSetupPrompt() }
    }

    @Test
    fun `an imported pin is marked handled, so the sweep leaves a removed game removed`() = runTest {
        ready()
        coEvery { gameRepository.getLauncherShortcut("com.winlator", "shortcut-1") } returns null
        coEvery { gameRepository.getByPlatform("windows") } returns emptyList()
        coEvery { gameRepository.upsert(any()) } returns 5L

        importer().importPinnedShortcut("com.winlator", "shortcut-1", "Some Game", changedAt = 1_000L)

        // The pin stays pinned (it is the launch handle), but it is spent: the next startup
        // sweep sees it as handled and will not re-create the row the user deleted.
        assertTrue(ledger.isHandled("com.winlator", "shortcut-1", changedAt = 1_000L))
    }

    @Test
    fun `re-adding from the host republishes the shortcut, which the ledger lets back in`() = runTest {
        ready()
        coEvery { gameRepository.getLauncherShortcut("com.winlator", "shortcut-1") } returns null
        coEvery { gameRepository.getByPlatform("windows") } returns emptyList()
        coEvery { gameRepository.upsert(any()) } returns 5L

        importer().importPinnedShortcut("com.winlator", "shortcut-1", "Some Game", changedAt = 1_000L)

        // Pressing "Add to Desktop" again updates the pin in place — a newer publish stamp.
        assertFalse(ledger.isHandled("com.winlator", "shortcut-1", changedAt = 2_000L))
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    @Test
    fun `a fork the user named a launcher routes its shortcuts to Windows Games`() = runTest {
        // Not installed under Robolectric, so it has no signer: the choice is keyed on null.
        val fork = "com.example.winlatorfork"
        assertFalse(importer(appContext).isPcLauncher(fork))

        choices.set(fork, signerSha256 = null, choice = LauncherChoice.WINLATOR)

        assertTrue(importer(appContext).isPcLauncher(fork))
    }

    @Test
    fun `Not a launcher keeps a catalog package's shortcuts out of Windows Games`() = runTest {
        assertTrue(importer(appContext).isPcLauncher("com.winlator.banner"))

        choices.set("com.winlator.banner", signerSha256 = null, choice = LauncherChoice.NOT_A_LAUNCHER)

        assertFalse(importer(appContext).isPcLauncher("com.winlator.banner"))
    }

    // ── Rows filed before the host was trusted ────────────────────────────────

    private fun androidShortcutRow(id: Long, title: String) = Game(
        id = id, title = title, platformId = "android", packageName = "com.winlator.banner",
        shortcutId = "4727de93", isManualEntry = true, contentType = GameContentType.ANDROID_APP,
    )

    @Test
    fun `a pin filed as an app in its host's collection moves to Windows Games`() = runTest {
        ready()
        coEvery { gameRepository.getLauncherShortcut("com.winlator.banner", "4727de93") } returns
            androidShortcutRow(11L, "Hellraiser")
        coEvery { collections.getAll() } returns listOf(
            GameCollection(id = 3L, name = "Shortcuts"), GameCollection(id = 4L, name = "Favourites"),
        )
        coEvery { collections.getCollectionIdsForGame(11L) } returns listOf(3L, 4L)
        coEvery { collections.addedAtByGame(3L) } returns emptyMap()
        val updated = slot<Game>()
        coEvery { gameRepository.upsert(capture(updated)) } returns 11L

        // Uninstalled under Robolectric, so the host's collection carries the "Shortcuts" fallback.
        val result = importer(appContext).importPinnedShortcut("com.winlator.banner", "4727de93", "Hellraiser")

        assertEquals(11L, result.gameId)
        assertFalse(result.added)
        assertEquals("windows", updated.captured.platformId)
        assertEquals(GameContentType.GAME, updated.captured.contentType)
        assertEquals("4727de93", updated.captured.shortcutId)
        // Only the auto-filed host collection lets go; a card the user chose keeps the game.
        coVerify { collections.removeGame(3L, 11L) }
        coVerify(exactly = 0) { collections.removeGame(4L, any()) }
        // The host collection held nothing else, so it goes with it.
        coVerify { collections.delete(3L) }
    }

    @Test
    fun `a host collection with other entries is kept`() = runTest {
        ready()
        coEvery { gameRepository.getLauncherShortcut("com.winlator.banner", "4727de93") } returns
            androidShortcutRow(11L, "Hellraiser")
        coEvery { collections.getAll() } returns listOf(GameCollection(id = 3L, name = "Shortcuts"))
        coEvery { collections.getCollectionIdsForGame(11L) } returns listOf(3L)
        coEvery { collections.addedAtByGame(3L) } returns mapOf(12L to 0L)
        coEvery { gameRepository.upsert(any()) } returns 11L

        importer(appContext).importPinnedShortcut("com.winlator.banner", "4727de93", "Hellraiser")

        coVerify { collections.removeGame(3L, 11L) }
        coVerify(exactly = 0) { collections.delete(any()) }
    }

    @Test
    fun `a pin already on Windows Games touches no collection`() = runTest {
        ready()
        coEvery { gameRepository.getLauncherShortcut("com.winlator", "shortcut-1") } returns
            windowsGame(7L, "Some Game").copy(packageName = "com.winlator", shortcutId = "shortcut-1")

        importer().importPinnedShortcut("com.winlator", "shortcut-1", "Some Game")

        coVerify(exactly = 0) { gameRepository.upsert(any()) }
        coVerify(exactly = 0) { collections.removeGame(any(), any()) }
    }

    // ── Legacy captures ───────────────────────────────────────────────────────

    private fun legacyUri(vararg extras: Pair<String, Any>): String =
        Intent("com.xiaoji.egggame.LAUNCH_GAME").apply {
            component = ComponentName("com.xiaoji.egggame", "com.xiaoji.egggame.DeepLinkActivity")
            extras.forEach { (k, v) ->
                when (v) {
                    is String -> putExtra(k, v)
                    is Int    -> putExtra(k, v)
                    is Boolean -> putExtra(k, v)
                }
            }
        }.toUri(Intent.URI_INTENT_SCHEME)

    @Test
    fun `legacy capture with an explicit steamAppId links steam`() = runTest {
        ready()
        val uri = legacyUri("steamAppId" to "1451090", "autoStartGame" to true)
        coEvery { gameRepository.getByIntentUri(uri) } returns null
        coEvery { gameRepository.getByPlatform("windows") } returns emptyList()
        coEvery { gameRepository.upsert(any()) } returns 11L

        val result = importer().importLegacyShortcut("com.xiaoji.egggame", "Tactics Ogre", uri)

        assertEquals(11L, result.gameId)
        assertTrue(result.added)
        coVerify { linker.linkSteam(11L, "1451090") }
        coVerify { gameRepository.updateStorefrontIdentity(11L, "STEAM", "1451090") }
    }

    @Test
    fun `legacy capture with only a localGameId never invents a steam link`() = runTest {
        ready()
        // GameHub internal ids are NOT Steam appids (live case: RESONANCE game_id=86019).
        val uri = legacyUri("localGameId" to "86019", "autoStartGame" to true)
        coEvery { gameRepository.getByIntentUri(uri) } returns null
        coEvery { gameRepository.getByPlatform("windows") } returns emptyList()
        coEvery { gameRepository.upsert(any()) } returns 12L

        importer().importLegacyShortcut("com.xiaoji.egggame", "Resonance of Fate", uri)

        coVerify(exactly = 0) { linker.linkSteam(any(), any()) }
        // A localGameId is the launcher's internal id, so there is no storefront identity to record.
        coVerify(exactly = 0) { gameRepository.updateStorefrontIdentity(any(), any(), any()) }
    }

    @Test
    fun `legacy capture dedupes by intent uri`() = runTest {
        ready()
        val uri = legacyUri("steamAppId" to "1451090")
        coEvery { gameRepository.getByIntentUri(uri) } returns
            windowsGame(3L, "Tactics Ogre").copy(launchIntentUri = uri)

        val result = importer().importLegacyShortcut("com.xiaoji.egggame", "Tactics Ogre", uri)

        assertEquals(3L, result.gameId)
        assertFalse(result.added)
        coVerify(exactly = 0) { gameRepository.upsert(any()) }
    }
}
