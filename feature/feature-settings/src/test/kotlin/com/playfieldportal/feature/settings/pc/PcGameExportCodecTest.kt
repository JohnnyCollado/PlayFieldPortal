package com.playfieldportal.feature.settings.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C18 task X.1 — the `.pfpgame` file that Export Manual Games writes and Scan Import Folder reads. */
class PcGameExportCodecTest {

    private val bannerHubGame = PcGameExport(
        title = "Portal 2",
        scrapedTitle = "Portal 2",
        userTitleOverride = "Portal 2 (Co-op)",
        launcherPackage = "banner.hub",
        launchIntentUri = "intent:#Intent;action=banner.hub.LAUNCH_GAME;S.localGameId=local_1f2e;end",
        storefront = "STEAM",
        storefrontGameId = "620",
        ssId = 425726L,
        steamGridDbId = 5483322L,
        artwork = listOf(
            PcGameExportArtwork(kind = "ICON", sortOrder = 0, portableName = "Portal 2 (Co-op)"),
            PcGameExportArtwork(kind = "SCREENSHOT", sortOrder = 1, portableName = "Portal 2 (Co-op)_01"),
        ),
    )

    private fun decodedExport(text: String): PcGameExport {
        val result = PcGameExportCodec.decode(text)
        check(result is PcGameExportDecode.Valid) { "expected a valid export, got $result" }
        return result.export
    }

    private fun rejected(text: String): PcGameExportDecode.Rejected {
        val result = PcGameExportCodec.decode(text)
        check(result is PcGameExportDecode.Rejected) { "expected a rejection, got $result" }
        return result
    }

    @Test
    fun `a game round-trips with its artwork names`() {
        val decoded = decodedExport(PcGameExportCodec.encode(bannerHubGame))

        assertEquals(bannerHubGame, decoded)
        assertEquals(listOf("Portal 2 (Co-op)", "Portal 2 (Co-op)_01"), decoded.artwork.map { it.portableName })
    }

    @Test
    fun `the written file names its format and version`() {
        val text = PcGameExportCodec.encode(bannerHubGame.copy(format = "something else", version = 99))

        assertTrue(text.contains("\"format\": \"pfp-pc-game\""))
        assertTrue(text.contains("\"version\": 1"))
    }

    @Test
    fun `an unknown field is ignored`() {
        val text = """
            {"format":"pfp-pc-game","version":1,"title":"Portal 2","launcherPackage":"banner.hub",
             "launchIntentUri":"intent:#Intent;end","addedInAFutureVersion":{"x":1}}
        """.trimIndent()

        assertEquals("Portal 2", decodedExport(text).title)
    }

    @Test
    fun `a newer version is refused with a message, never half read`() {
        val text = PcGameExportCodec.encode(bannerHubGame).replace("\"version\": 1", "\"version\": 2")

        assertTrue(rejected(text).reason.contains("newer"))
    }

    @Test
    fun `an entry with no title, no launcher, or nothing to launch or match is rejected`() {
        rejected("""{"format":"pfp-pc-game","version":1,"title":"  ","launcherPackage":"banner.hub","launchIntentUri":"intent:#Intent;end"}""")
        rejected("""{"format":"pfp-pc-game","version":1,"title":"Portal 2","launchIntentUri":"intent:#Intent;end"}""")
        rejected("""{"format":"pfp-pc-game","version":1,"title":"Portal 2","launcherPackage":"banner.hub"}""")
        rejected("""{"format":"pfp-pc-game","version":1,"title":"Portal 2","launcherPackage":"banner.hub","launchIntentUri":" ","shortcutId":""}""")
    }

    @Test
    fun `a pin entry keeps its shortcut id and loses any launch intent`() {
        val text = PcGameExportCodec.encode(bannerHubGame.copy(shortcutId = "game_620"))

        val pin = decodedExport(text)

        assertTrue(pin.isPin)
        assertEquals("game_620", pin.shortcutId)
        assertNull("a pin entry must never be able to launch anything", pin.launchIntentUri)
    }

    @Test
    fun `another format, an oversized body and a non-JSON body are rejected, not a crash`() {
        rejected("""{"format":"pfp-windows-games","version":1,"title":"Portal 2","launcherPackage":"banner.hub","launchIntentUri":"intent:#Intent;end"}""")
        rejected("""{"version":1,"title":"Portal 2","launcherPackage":"banner.hub","launchIntentUri":"intent:#Intent;end"}""")
        rejected("620")
        rejected("""[{"format":"pfp-pc-game"}]""")
        rejected("{\"format\":\"pfp-pc-ga")
        rejected(PcGameExportCodec.encode(bannerHubGame.copy(title = "x".repeat(PcGameExportCodec.MAX_CHARS))))
    }

    @Test
    fun `too many artwork items are rejected`() {
        val items = List(PcGameExportCodec.MAX_ARTWORK_ITEMS + 1) {
            PcGameExportArtwork(kind = "SCREENSHOT", sortOrder = it, portableName = "Portal 2_$it")
        }

        rejected(PcGameExportCodec.encode(bannerHubGame.copy(artwork = items)))
    }

    @Test
    fun `an artwork item with no kind, no name or a negative position is dropped`() {
        val text = PcGameExportCodec.encode(
            bannerHubGame.copy(
                artwork = listOf(
                    PcGameExportArtwork(kind = "ICON", sortOrder = 0, portableName = "Portal 2"),
                    PcGameExportArtwork(kind = "LOGO", sortOrder = 0, portableName = "  "),
                    PcGameExportArtwork(kind = "", sortOrder = 0, portableName = "Portal 2"),
                    PcGameExportArtwork(kind = "HERO", sortOrder = -1, portableName = "Portal 2"),
                ),
            ),
        )

        assertEquals(listOf("ICON"), decodedExport(text).artwork.map { it.kind })
    }

    @Test
    fun `blank optional values and non-positive provider ids read as absent`() {
        val text = """
            {"format":"pfp-pc-game","version":1,"title":" Portal 2 ","launcherPackage":" banner.hub ",
             "launchIntentUri":"intent:#Intent;end","scrapedTitle":"","userTitleOverride":"  ",
             "storefront":"","storefrontGameId":" ","ssId":0,"tgdbId":-4}
        """.trimIndent()

        val export = decodedExport(text)

        assertEquals("Portal 2", export.title)
        assertEquals("banner.hub", export.launcherPackage)
        assertNull(export.scrapedTitle)
        assertNull(export.userTitleOverride)
        assertNull(export.storefront)
        assertNull(export.storefrontGameId)
        assertNull(export.ssId)
        assertNull(export.tgdbId)
    }
}
