package com.playfieldportal.feature.launcher

import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.core.domain.model.Platform
import com.playfieldportal.core.domain.repository.GameRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Home-screen shortcuts from console emulators (X360 Mobile, XenDroid) land on that console's
 * platform Memory Card — never in a custom card named after the app. A shortcut for a game the
 * library already has links to it instead of adding a second entry.
 */
class ConsoleShortcutImporterTest {

    private val games = mockk<GameRepository>(relaxed = true)
    private val cards = mockk<MemoryCardRepository>(relaxed = true)
    private val importer = ConsoleShortcutImporter(games, cards)

    private val doaPath = "/storage/emulated/0/PFP/Roms/xbox360/Dead or Alive 4 (Asia) (En,Ja,Fr,De,Es,It,Zh,Ko).iso"
    private val doa = Game(id = 12, title = "Dead or Alive 4 (Asia) (En,Ja,Fr,De,Es,It,Zh,Ko)", platformId = "x360", romPath = doaPath)

    private fun library(vararg g: Game) {
        coEvery { games.getLauncherShortcut(any(), any()) } returns null
        coEvery { games.getByPlatform("x360") } returns g.toList()
        coEvery { cards.unconfiguredPlatforms() } returns emptyList()
    }

    @Test
    fun `X360 Mobile and XenDroid are Xbox 360 shortcut hosts, other apps are not`() {
        assertEquals("x360", ConsoleShortcutHosts.platformFor("emu.x360mobile.com"))
        assertEquals("x360", ConsoleShortcutHosts.platformFor("xendroid.compose"))
        assertEquals("x360", ConsoleShortcutHosts.platformFor("xendroid.compose.debug"))
        assertNull(ConsoleShortcutHosts.platformFor("org.dolphinemu.dolphinemu"))
        assertNull(ConsoleShortcutHosts.platformFor(null))
    }

    @Test
    fun `a XenDroid shortcut links to the library game at the same path`() = runTest {
        // XenDroid's shortcut id is the game's absolute path.
        library(Game(id = 3, title = "Halo 3", platformId = "x360", romPath = "/roms/halo3.iso"), doa)

        val result = importer.importPinnedShortcut("xendroid.compose", doaPath, "DEAD OR ALIVE 4")

        assertEquals(ConsoleShortcutImportResult(gameId = 12, linked = true), result)
        coVerify { games.setPreferredEmulator(12, "xendroid.compose") }
        coVerify(exactly = 0) { games.upsert(any()) }
    }

    @Test
    fun `an X360 Mobile shortcut links to the library game with the same name`() = runTest {
        // X360 Mobile's id is an opaque hash, so the label is the only handle; region tags and
        // case never block the match.
        library(doa)

        val result = importer.importPinnedShortcut("emu.x360mobile.com", "x360-game-92c43fe2", "DEAD OR ALIVE 4")

        assertEquals(ConsoleShortcutImportResult(gameId = 12, linked = true), result)
        coVerify { games.setPreferredEmulator(12, "emu.x360mobile.com") }
    }

    @Test
    fun `a shortcut for a game not in the library is added to the Xbox 360 card`() = runTest {
        library(doa)
        val added = slot<Game>()
        coEvery { games.upsert(capture(added)) } returns 40

        val result = importer.importPinnedShortcut("emu.x360mobile.com", "x360-game-abc", "Halo 3")

        assertEquals(ConsoleShortcutImportResult(gameId = 40, linked = false), result)
        with(added.captured) {
            assertEquals("Halo 3", title)
            assertEquals("x360", platformId)
            assertEquals("emu.x360mobile.com", packageName)
            assertEquals("x360-game-abc", shortcutId)
            assertEquals(GameContentType.GAME, contentType)
            assertTrue(isManualEntry)
        }
        coVerify { cards.recountGames("x360") }
    }

    @Test
    fun `the Xbox 360 card is created when the library has none yet`() = runTest {
        library()
        coEvery { cards.unconfiguredPlatforms() } returns listOf(
            Platform(id = "x360", name = "Xbox 360", shortName = "X360", iconRes = null, accentColor = 0L),
        )
        coEvery { games.upsert(any()) } returns 41

        importer.importPinnedShortcut("xendroid.compose", "/roms/halo3.iso", "Halo 3")

        coVerify { cards.addCard(platformId = "x360", displayName = "Xbox 360 Memory Card", romDirectory = null, emulatorId = null) }
    }

    @Test
    fun `pinning the same shortcut again changes nothing`() = runTest {
        library()
        val pinned = Game(id = 50, title = "Halo 3", platformId = "x360", packageName = "emu.x360mobile.com", shortcutId = "x360-game-abc")
        coEvery { games.getLauncherShortcut("emu.x360mobile.com", "x360-game-abc") } returns pinned

        val result = importer.importPinnedShortcut("emu.x360mobile.com", "x360-game-abc", "Halo 3")

        assertEquals(ConsoleShortcutImportResult(gameId = 50, linked = false), result)
        coVerify(exactly = 0) { games.upsert(any()) }
        coVerify(exactly = 0) { games.setPreferredEmulator(any(), any()) }
    }

    @Test
    fun `a name match never crosses into a different game`() = runTest {
        library(doa)
        coEvery { games.upsert(any()) } returns 42

        val result = importer.importPinnedShortcut("emu.x360mobile.com", "x360-game-xyz", "DEAD OR ALIVE 4 Ultimate")

        assertFalse(result.linked)
        coVerify(exactly = 0) { games.setPreferredEmulator(12, any()) }
    }
}
