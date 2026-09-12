package com.playfieldportal.feature.settings.pc

import com.playfieldportal.core.domain.model.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C18 task X.4 — what one `.pfpgame` entry does to the library. */
class PcGameImportPlannerTest {

    private val fileIntent = "intent:#Intent;action=banner.hub.LAUNCH_GAME;component=banner.hub/com.xiaoji.egggame.DeepLinkActivity;S.localGameId=local_1f2e;B.autoStartGame=true;launchFlags=0x10000000;end"
    private val sanitizedIntent = "intent:#Intent;action=banner.hub.LAUNCH_GAME;component=banner.hub/com.xiaoji.egggame.DeepLinkActivity;S.localGameId=local_1f2e;B.autoStartGame=true;end"

    private val trusted = LaunchCheck(launcherVerified = true, intentPackage = "banner.hub", sanitizedIntentUri = sanitizedIntent)

    private val entry = PcGameExport(
        title = "Portal 2",
        scrapedTitle = "Portal 2",
        userTitleOverride = "Portal 2 (Co-op)",
        launcherPackage = "banner.hub",
        launchIntentUri = fileIntent,
        ssId = 425726L,
        steamGridDbId = 5483322L,
        artwork = listOf(
            PcGameExportArtwork(kind = "ICON", sortOrder = 0, portableName = "Portal 2 (Co-op)"),
            PcGameExportArtwork(kind = "SCREENSHOT", sortOrder = 1, portableName = "Portal 2 (Co-op)_01"),
        ),
    )

    private fun windowsGame(
        id: Long,
        title: String = "Portal 2",
        packageName: String? = "banner.hub",
        launchIntentUri: String? = null,
        shortcutId: String? = null,
        storefront: String? = null,
        storefrontGameId: String? = null,
        ssId: Long? = null,
        userTitleOverride: String? = null,
    ) = Game(
        id = id,
        title = title,
        platformId = "windows",
        packageName = packageName,
        launchIntentUri = launchIntentUri,
        shortcutId = shortcutId,
        storefront = storefront,
        storefrontGameId = storefrontGameId,
        ssId = ssId,
        userTitleOverride = userTitleOverride,
    )

    // ── Trusting the launch intent ────────────────────────────────────────────

    @Test
    fun `an intent for a launcher that is not installed or not verified is skipped`() {
        val decision = PcGameImportPlanner.plan(entry, trusted.copy(launcherVerified = false), emptyList())

        assertEquals(PcGameImportDecision.Skip(PcGameImportSkip.LAUNCHER_UNAVAILABLE), decision)
    }

    @Test
    fun `an entry whose intent targets another app than its launcher is untrusted`() {
        val decision = PcGameImportPlanner.plan(entry, trusted.copy(intentPackage = "com.evil.app"), emptyList())

        assertEquals(PcGameImportDecision.Skip(PcGameImportSkip.UNTRUSTED_INTENT), decision)
    }

    @Test
    fun `an intent that did not parse or could not be made safe is untrusted`() {
        assertEquals(
            PcGameImportDecision.Skip(PcGameImportSkip.UNTRUSTED_INTENT),
            PcGameImportPlanner.plan(entry, trusted.copy(intentPackage = null, sanitizedIntentUri = null), emptyList()),
        )
        assertEquals(
            PcGameImportDecision.Skip(PcGameImportSkip.UNTRUSTED_INTENT),
            PcGameImportPlanner.plan(entry, trusted.copy(sanitizedIntentUri = null), emptyList()),
        )
    }

    // ── Creating and filling ──────────────────────────────────────────────────

    @Test
    fun `no match creates exactly one game, launched by the sanitized intent`() {
        val decision = PcGameImportPlanner.plan(entry, trusted, listOf(windowsGame(9, title = "Half-Life", launchIntentUri = "intent:#Intent;S.x=1;end")))

        check(decision is PcGameImportDecision.Create) { "expected a create, got $decision" }
        val game = decision.game
        assertEquals("Portal 2", game.title)
        assertEquals("Portal 2 (Co-op)", game.userTitleOverride)
        assertEquals("banner.hub", game.packageName)
        assertEquals("windows", game.platformId)
        assertTrue(game.isManualEntry)
        assertEquals(sanitizedIntent, game.launchIntentUri)
        assertEquals(425726L, game.ssId)
        assertEquals(0L, game.id)
    }

    @Test
    fun `a populated column is never overwritten, and a missing one is filled`() {
        val existing = windowsGame(1, launchIntentUri = sanitizedIntent, ssId = 111L, userTitleOverride = "My Portal")

        val decision = PcGameImportPlanner.plan(entry, trusted, listOf(existing))

        check(decision is PcGameImportDecision.Fill) { "expected a fill, got $decision" }
        assertTrue(decision.changed)
        assertEquals(1L, decision.game.id)
        assertEquals(111L, decision.game.ssId)
        assertEquals("My Portal", decision.game.userTitleOverride)
        assertEquals("Portal 2", decision.game.scrapedTitle)
        assertEquals(5483322L, decision.game.steamGridDbId)
    }

    @Test
    fun `a game that already has everything is matched without a change`() {
        val complete = windowsGame(1, launchIntentUri = fileIntent, ssId = 425726L, userTitleOverride = "Portal 2 (Co-op)")
            .copy(scrapedTitle = "Portal 2", steamGridDbId = 5483322L)

        val decision = PcGameImportPlanner.plan(entry, trusted, listOf(complete))

        assertEquals(PcGameImportDecision.Fill(complete, changed = false), decision)
    }

    @Test
    fun `a game created earlier in the same pass is matched, not duplicated`() {
        val createdByLauncherFile = windowsGame(7, title = "portal 2", launchIntentUri = sanitizedIntent)

        val decision = PcGameImportPlanner.plan(entry, trusted, listOf(createdByLauncherFile))

        assertTrue(decision is PcGameImportDecision.Fill && decision.game.id == 7L)
    }

    @Test
    fun `a storefront pair matches, and a Steam 620 never matches a GOG 620`() {
        val steamEntry = entry.copy(storefront = "STEAM", storefrontGameId = "620", userTitleOverride = null, scrapedTitle = null, title = "Something Else")
        val gog = windowsGame(1, title = "Other", launchIntentUri = "intent:#Intent;S.x=1;end", storefront = "GOG", storefrontGameId = "620")
        val steam = windowsGame(2, title = "Other 2", launchIntentUri = "intent:#Intent;S.x=2;end", storefront = "STEAM", storefrontGameId = "620")

        val decision = PcGameImportPlanner.plan(steamEntry, trusted, listOf(gog, steam))

        assertTrue(decision is PcGameImportDecision.Fill && decision.game.id == 2L)
        assertTrue(PcGameImportPlanner.plan(steamEntry, trusted, listOf(gog)) is PcGameImportDecision.Create)
    }

    @Test
    fun `a BannerHub entry matches a game now launched through GameHub Lite, by title`() {
        val onGameHubLite = windowsGame(3, title = "Portal 2", packageName = "gamehub.lite", launchIntentUri = "intent:#Intent;action=gamehub.lite.LAUNCH_GAME;end")

        val decision = PcGameImportPlanner.plan(entry, trusted, listOf(onGameHubLite))

        assertTrue(decision is PcGameImportDecision.Fill && decision.game.id == 3L)
    }

    @Test
    fun `two games that fit by title are ambiguous, and another launcher's game is no fit`() {
        val a = windowsGame(1, launchIntentUri = "intent:#Intent;S.x=1;end")
        val b = windowsGame(2, title = "PORTAL-2", launchIntentUri = "intent:#Intent;S.x=2;end")
        val gameNative = windowsGame(3, packageName = "app.gamenative", launchIntentUri = "intent:#Intent;S.x=3;end")

        assertEquals(PcGameImportDecision.Skip(PcGameImportSkip.AMBIGUOUS), PcGameImportPlanner.plan(entry, trusted, listOf(a, b)))
        assertTrue(PcGameImportPlanner.plan(entry, trusted, listOf(gameNative)) is PcGameImportDecision.Create)
    }

    @Test
    fun `a non-Windows game is never a match`() {
        val psx = windowsGame(1, launchIntentUri = sanitizedIntent).copy(platformId = "psx")

        assertTrue(PcGameImportPlanner.plan(entry, trusted, listOf(psx)) is PcGameImportDecision.Create)
    }

    // ── Pins ──────────────────────────────────────────────────────────────────

    private val pinEntry = entry.copy(launchIntentUri = null, shortcutId = "game_620", userTitleOverride = null)

    @Test
    fun `a pin entry matches by launcher package and shortcut id, and is filled`() {
        val pin = windowsGame(4, title = "Portal 2 (pinned)", shortcutId = "game_620")

        val decision = PcGameImportPlanner.plan(pinEntry, launch = null, windowsGames = listOf(pin))

        check(decision is PcGameImportDecision.Fill) { "expected a fill, got $decision" }
        assertEquals(4L, decision.game.id)
        assertEquals(425726L, decision.game.ssId)
        assertNull(decision.game.launchIntentUri)
    }

    @Test
    fun `a pin entry is never created, and an unmatched one is skipped`() {
        val decision = PcGameImportPlanner.plan(pinEntry, launch = null, windowsGames = listOf(windowsGame(1, title = "Half-Life", shortcutId = "hl")))

        assertEquals(PcGameImportDecision.Skip(PcGameImportSkip.PIN_NOT_IN_LIBRARY), decision)
    }

    // ── Claims ────────────────────────────────────────────────────────────────

    @Test
    fun `an entry's artwork becomes claims keyed by lowercased name`() {
        val claims = PcGameArtworkClaims().apply { add(entry, gameId = 12) }.toMap()

        assertEquals(
            mapOf(
                Triple("windows", "ICON", "portal 2 (co-op)") to 12L,
                Triple("windows", "SCREENSHOT", "portal 2 (co-op)_01") to 12L,
            ),
            claims,
        )
    }

    @Test
    fun `a name two games claim is claimed by neither`() {
        val claims = PcGameArtworkClaims().apply {
            add(entry, gameId = 12)
            add(entry.copy(artwork = listOf(PcGameExportArtwork(kind = "ICON", portableName = "PORTAL 2 (CO-OP)"))), gameId = 13)
            add(entry.copy(artwork = listOf(PcGameExportArtwork(kind = "ICON", portableName = "Portal 2 (Co-op)"))), gameId = 14)
        }.toMap()

        assertFalse(Triple("windows", "ICON", "portal 2 (co-op)") in claims)
        assertEquals(12L, claims[Triple("windows", "SCREENSHOT", "portal 2 (co-op)_01")])
    }

    private fun record(gameId: Long, type: String, portableName: String) =
        com.playfieldportal.core.data.database.entity.ArtworkRecordEntity(
            gameId = gameId,
            platformId = "windows",
            artworkType = type,
            portableName = portableName,
            relativePath = "windows/x/$portableName.png",
            documentUri = "content://x/$portableName",
            source = "relink",
        )

    @Test
    fun `a claim with no matching record is unresolved, and relink is needed`() {
        val claims = PcGameArtworkClaims().apply { add(entry, gameId = 12) }.toMap()

        // After a fresh install there are no records at all; a record under another game or kind
        // does not fulfil the claim either.
        val unresolved = PcGameArtworkClaims.unresolved(
            claims,
            mapOf(12L to listOf(record(12, "HERO", "Portal 2 (Co-op)")), 13L to listOf(record(13, "ICON", "Portal 2 (Co-op)"))),
        )

        assertEquals(claims, unresolved)
    }

    @Test
    fun `claims every record already fulfils need no relink, whatever the name's case`() {
        val claims = PcGameArtworkClaims().apply { add(entry, gameId = 12) }.toMap()

        val unresolved = PcGameArtworkClaims.unresolved(
            claims,
            mapOf(12L to listOf(record(12, "ICON", "portal 2 (co-op)"), record(12, "SCREENSHOT", "Portal 2 (Co-op)_01"))),
        )

        assertTrue(unresolved.isEmpty())
    }
}
