package com.playfieldportal.feature.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.data.repository.WindowsLibrarySetup
import com.playfieldportal.core.data.repository.WindowsSetupState
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.core.domain.repository.GameRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
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

    private fun importer() = PcShortcutImporter(
        mockk<Context>(relaxed = true), gameRepository, memoryCards, windowsLibrary, linker,
    )

    private fun ready() {
        coEvery { windowsLibrary.ensure() } returns WindowsSetupState.Ready("/storage/emulated/0/Roms/windows")
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
