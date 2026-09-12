package com.playfieldportal.feature.settings.pc

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.feature.library.scanner.PcExportFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** C18 task X.2 — which Windows games Export Manual Games writes a file for. */
class ManualGameExportSelectorTest {

    private fun windowsGame(
        id: Long,
        title: String = "Game $id",
        launchIntentUri: String? = null,
        shortcutId: String? = null,
        storefront: String? = null,
        storefrontGameId: String? = null,
        platformId: String = "windows",
    ) = Game(
        id = id,
        title = title,
        platformId = platformId,
        packageName = "banner.hub",
        launchIntentUri = launchIntentUri,
        shortcutId = shortcutId,
        storefront = storefront,
        storefrontGameId = storefrontGameId,
    )

    private fun exportFile(extension: String, idContent: String? = null, rawPath: String? = null) =
        PcExportFile(title = "file", extension = extension, idContent = idContent, rawPath = rawPath, uri = "content://x")

    private fun select(
        games: List<Game>,
        withArtwork: Set<Long> = emptySet(),
        files: List<PcExportFile> = emptyList(),
        reproduced: Set<String> = emptySet(),
    ) = ManualGameExportSelector.select(games, withArtwork, files, reproduced)

    private val gameHubLocal = "intent:#Intent;action=banner.hub.LAUNCH_GAME;S.localGameId=local_1f2e;B.autoStartGame=true;end"
    private val gameNativeSteam620 = "intent:#Intent;action=app.gamenative.LAUNCH_GAME;i.app_id=620;S.game_source=STEAM;end"

    @Test
    fun `a pin with artwork records is exported as a pin entry, and a pin with none is not`() {
        val withArt = windowsGame(1, shortcutId = "game_620")
        val withoutArt = windowsGame(2, shortcutId = "game_730")

        val selection = select(listOf(withArt, withoutArt), withArtwork = setOf(1))

        assertEquals(listOf(withArt), selection.exported)
        assertEquals(1, selection.skipped)
    }

    @Test
    fun `a row with no launch intent and no shortcut is never exported`() {
        assertTrue(select(listOf(windowsGame(1)), withArtwork = setOf(1)).exported.isEmpty())
    }

    @Test
    fun `a row whose intent a launcher export file reproduces is skipped`() {
        val game = windowsGame(1, launchIntentUri = gameHubLocal)

        assertTrue(select(listOf(game), reproduced = setOf(gameHubLocal)).exported.isEmpty())
    }

    @Test
    fun `a row whose storefront pair matches a steam file is skipped, but a Steam 620 file never skips a GOG 620 row`() {
        val steam = windowsGame(1, launchIntentUri = "intent:#Intent;S.x=1;end", storefront = "STEAM", storefrontGameId = "620")
        val gog = windowsGame(2, launchIntentUri = "intent:#Intent;S.x=2;end", storefront = "GOG", storefrontGameId = "620")

        val selection = select(listOf(steam, gog), files = listOf(exportFile("steam", idContent = "620\n")))

        assertEquals(listOf(gog), selection.exported)
    }

    @Test
    fun `a GameNative game added by id is recognised by the pair in its intent`() {
        // Add by ID records no storefront columns.
        val game = windowsGame(1, launchIntentUri = gameNativeSteam620)

        assertEquals(listOf(game), select(listOf(game)).exported)
        assertTrue(select(listOf(game), files = listOf(exportFile("steam", idContent = "620"))).exported.isEmpty())
    }

    @Test
    fun `a Winlator row whose shortcut path matches a desktop file is skipped, non-ASCII names included`() {
        val path = "/storage/emulated/0/Winlator/Pokémon ★.desktop"
        val game = windowsGame(
            1,
            launchIntentUri = "intent:#Intent;component=com.winlator/.MainActivity;" +
                "S.shortcut_path=%2Fstorage%2Femulated%2F0%2FWinlator%2FPok%C3%A9mon%20%E2%98%85.desktop;end",
        )

        assertTrue(select(listOf(game), files = listOf(exportFile("desktop", rawPath = path))).exported.isEmpty())
        assertEquals(listOf(game), select(listOf(game), files = listOf(exportFile("desktop", rawPath = "/other.desktop"))).exported)
    }

    @Test
    fun `a GameHub game added by its local id is exported`() {
        val game = windowsGame(1, launchIntentUri = gameHubLocal)

        val selection = select(listOf(game), files = listOf(exportFile("steam", idContent = "620")))

        assertEquals(listOf(game), selection.exported)
        assertEquals(0, selection.skipped)
    }

    @Test
    fun `a non-Windows game is never exported`() {
        val game = windowsGame(1, launchIntentUri = gameHubLocal, platformId = "psx")

        assertTrue(select(listOf(game), withArtwork = setOf(1)).exported.isEmpty())
    }
}
