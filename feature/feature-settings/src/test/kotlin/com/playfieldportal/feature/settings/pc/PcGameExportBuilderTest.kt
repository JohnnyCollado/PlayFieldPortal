package com.playfieldportal.feature.settings.pc

import com.playfieldportal.core.data.database.entity.ArtworkRecordEntity
import com.playfieldportal.core.domain.model.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C18 task X.3 — the pure half of Export Manual Games: file names and content. */
class PcGameExportBuilderTest {

    private val gameHubIntent = "intent:#Intent;action=banner.hub.LAUNCH_GAME;S.localGameId=local_1f2e;end"

    private fun game(
        id: Long,
        title: String = "Portal 2",
        userTitleOverride: String? = null,
        packageName: String? = "banner.hub",
        launchIntentUri: String? = gameHubIntent,
        shortcutId: String? = null,
    ) = Game(
        id = id,
        title = title,
        platformId = "windows",
        packageName = packageName,
        launchIntentUri = launchIntentUri,
        shortcutId = shortcutId,
        userTitleOverride = userTitleOverride,
        ssId = 425726L,
    )

    private fun record(gameId: Long, type: String, sortOrder: Int, portableName: String) = ArtworkRecordEntity(
        gameId = gameId,
        platformId = "windows",
        artworkType = type,
        sortOrder = sortOrder,
        portableName = portableName,
        relativePath = "windows/x/$portableName.png",
        documentUri = "content://x/$portableName",
        source = "user",
    )

    @Test
    fun `a game with two screenshots exports two artwork items, ordered by kind then position`() {
        val artwork = mapOf(
            1L to listOf(
                record(1, "SCREENSHOT", 1, "Portal 2_01"),
                record(1, "SCREENSHOT", 0, "Portal 2"),
                record(1, "ICON", 0, "Portal 2"),
            ),
        )

        val file = PcGameExportBuilder.build(listOf(game(1)), artwork).single()

        assertEquals(
            listOf(Triple("ICON", 0, "Portal 2"), Triple("SCREENSHOT", 0, "Portal 2"), Triple("SCREENSHOT", 1, "Portal 2_01")),
            file.export.artwork.map { Triple(it.kind, it.sortOrder, it.portableName) },
        )
        assertEquals(425726L, file.export.ssId)
        assertEquals(gameHubIntent, file.export.launchIntentUri)
    }

    @Test
    fun `a game with no artwork exports an empty list`() {
        val file = PcGameExportBuilder.build(listOf(game(1)), emptyMap()).single()

        assertTrue(file.export.artwork.isEmpty())
    }

    @Test
    fun `games that share a name get numbered files, ignoring case`() {
        val files = PcGameExportBuilder.build(
            listOf(game(1), game(2, title = "portal 2"), game(3)),
            emptyMap(),
        )

        assertEquals(listOf("Portal 2.pfpgame", "portal 2 (2).pfpgame", "Portal 2 (3).pfpgame"), files.map { it.fileName })
    }

    @Test
    fun `the file is named after the display title, made safe for the file system`() {
        val file = PcGameExportBuilder.build(listOf(game(1, userTitleOverride = "Half-Life: Alyx")), emptyMap()).single()

        assertEquals("Half-Life Alyx.pfpgame", file.fileName)
        assertEquals("Portal 2", file.export.title)
        assertEquals("Half-Life: Alyx", file.export.userTitleOverride)
    }

    @Test
    fun `a pin keeps its shortcut id, carries no intent, and reads back as a pin entry`() {
        val pin = game(1, launchIntentUri = null, shortcutId = "game_620")

        val file = PcGameExportBuilder.build(listOf(pin), mapOf(1L to listOf(record(1, "ICON", 0, "Portal 2")))).single()
        val decoded = PcGameExportCodec.decode(PcGameExportCodec.encode(file.export))

        assertNull(file.export.launchIntentUri)
        assertTrue(decoded is PcGameExportDecode.Valid && decoded.export.isPin)
    }

    // ── One game at a time (task X.7) ─────────────────────────────────────────

    private fun exportOf(game: Game) = checkNotNull(PcGameExportBuilder.exportFor(game, emptyList()))

    @Test
    fun `the same launch intent is the same game, even after sanitizing changed its text`() {
        val game = game(1)

        assertTrue(PcGameExportBuilder.isSameGame(exportOf(game), game))
        // Flags stripped by the sanitizer; the extras that name the game are unchanged.
        val sanitized = game.copy(launchIntentUri = "intent:#Intent;component=banner.hub/x.Y;S.localGameId=local_1f2e;end")
        assertTrue(PcGameExportBuilder.isSameGame(exportOf(game), sanitized))
    }

    @Test
    fun `the same launcher and shortcut id is the same pin`() {
        val pin = game(1, launchIntentUri = null, shortcutId = "game_620")

        assertTrue(PcGameExportBuilder.isSameGame(exportOf(pin), pin.copy(title = "Renamed")))
        assertFalse(PcGameExportBuilder.isSameGame(exportOf(pin), pin.copy(shortcutId = "game_730")))
        assertFalse(PcGameExportBuilder.isSameGame(exportOf(pin), pin.copy(packageName = "gamehub.lite")))
    }

    @Test
    fun `another game with the same title is not the same game`() {
        val mine = game(1)
        val other = game(2, launchIntentUri = "intent:#Intent;action=banner.hub.LAUNCH_GAME;S.localGameId=local_9a9a;end")
        val noExtras = game(3, launchIntentUri = "intent:#Intent;action=banner.hub.LAUNCH_GAME;end")

        assertFalse(PcGameExportBuilder.isSameGame(exportOf(mine), other))
        assertFalse(PcGameExportBuilder.isSameGame(exportOf(noExtras), noExtras.copy(launchIntentUri = "intent:#Intent;end")))
    }

    @Test
    fun `a free name is used, and a name holding this game's own file is reused`() {
        val game = game(1)

        assertEquals("Portal 2.pfpgame", PcGameExportBuilder.fileNameFor(game, emptyMap()))
        assertEquals("Portal 2.pfpgame", PcGameExportBuilder.fileNameFor(game, mapOf("portal 2.pfpgame" to exportOf(game))))
    }

    @Test
    fun `a name holding another game's file, or an unreadable one, moves on to a numbered name`() {
        val game = game(1)
        val other = exportOf(game(2, launchIntentUri = "intent:#Intent;S.localGameId=local_9a9a;end"))

        assertEquals(
            "Portal 2 (2).pfpgame",
            PcGameExportBuilder.fileNameFor(game, mapOf("portal 2.pfpgame" to other)),
        )
        assertEquals(
            "Portal 2 (3).pfpgame",
            PcGameExportBuilder.fileNameFor(game, mapOf("portal 2.pfpgame" to other, "portal 2 (2).pfpgame" to null)),
        )
    }

    @Test
    fun `a game the import would reject gets no file`() {
        val files = PcGameExportBuilder.build(
            listOf(game(1, packageName = null), game(2, packageName = " "), game(3, launchIntentUri = null)),
            emptyMap(),
        )

        assertTrue(files.isEmpty())
    }
}
