package com.playfieldportal.feature.launcher

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Intent shapes per launcher and generation, pinned to the manifest-verified contracts
 * (docs/windows-library-refactor-plan.md section 3). Robolectric supplies real Intent extras.
 */
@RunWith(RobolectricTestRunner::class)
class PcLauncherAdapterTest {

    private fun gameHubAdapter(generation: GameHubGeneration): PcLauncherAdapter =
        PcLauncherAdapters.gameHubAdapterFor(PcLauncherType.GAMEHUB_LITE) { generation }

    // ── GameHub family ────────────────────────────────────────────────────────

    @Test
    fun `v6 targets the xiaoji deep-link dispatcher`() {
        val intent = gameHubAdapter(GameHubGeneration.V6)
            .buildLaunchIntent("com.xiaoji.egggame", "1451090", null)!!
        assertEquals("com.xiaoji.egggame.LAUNCH_GAME", intent.action)
        assertEquals(PcLauncherCatalog.V6_DEEP_LINK_ACTIVITY, intent.component?.className)
        assertEquals("1451090", intent.getStringExtra("localGameId"))
        // V6 keeps its proven single-extra contract for numeric ids.
        assertNull(intent.getStringExtra("steamAppId"))
        assertTrue(intent.getBooleanExtra("autoStartGame", false))
    }

    @Test
    fun `v5 targets the exported xj game detail activity`() {
        val intent = gameHubAdapter(GameHubGeneration.V5)
            .buildLaunchIntent("com.ludashi.aibench", "2753970", "STEAM")!!
        assertEquals("com.ludashi.aibench.LAUNCH_GAME", intent.action)
        assertEquals(PcLauncherCatalog.V5_GAME_DETAIL_ACTIVITY, intent.component?.className)
        assertEquals("2753970", intent.getStringExtra("steamAppId"))
        assertTrue(intent.getBooleanExtra("autoStartGame", false))
    }

    @Test
    fun `steam source selects steamAppId over localGameId`() {
        val intent = gameHubAdapter(GameHubGeneration.V6)
            .buildLaunchIntent("com.xiaoji.egggame", "524220", "STEAM")!!
        assertEquals("524220", intent.getStringExtra("steamAppId"))
        assertNull(intent.getStringExtra("localGameId"))
    }

    // Live-fired on Ludashi 5.1.7: a bare numeric id may be a Steam app id or a launcher-local
    // int, and the wrong namespace opens an empty stub — so V5 sends both and the launcher
    // resolves whichever matches.
    @Test
    fun `v5 numeric add-by-id sends both namespaces`() {
        val intent = gameHubAdapter(GameHubGeneration.V5)
            .buildLaunchIntent("com.ludashi.aibench", "993090", null)!!
        assertEquals("993090", intent.getStringExtra("steamAppId"))
        assertEquals("993090", intent.getStringExtra("localGameId"))
    }

    // Live-fired on Ludashi 5.1.7: manually added local EXEs carry a local_<uuid> string id
    // (Copy button on the game's page) that launches via localGameId verbatim.
    @Test
    fun `local uuid ids pass through as localGameId on both generations`() {
        val id = "local_0c81a609-d158-4b86-bc85-fc642c52c411"
        for (generation in GameHubGeneration.entries) {
            val intent = gameHubAdapter(generation)
                .buildLaunchIntent("com.ludashi.aibench", " $id ", null)!!
            assertEquals(id, intent.getStringExtra("localGameId"))
            assertNull(intent.getStringExtra("steamAppId"))
            assertTrue(intent.getBooleanExtra("autoStartGame", false))
        }
    }

    @Test
    fun `pm-less resolution assumes v6`() {
        val intent = PcLauncherAdapters.forType(PcLauncherType.BANNERHUB_V6)!!
            .buildLaunchIntent("banner.hub", "86019", null)!!
        assertEquals(PcLauncherCatalog.V6_DEEP_LINK_ACTIVITY, intent.component?.className)
    }

    @Test
    fun `malformed ids are rejected`() {
        val adapter = PcLauncherAdapters.forType(PcLauncherType.GAMEHUB_LITE)!!
        assertNull(adapter.buildLaunchIntent("com.xiaoji.egggame", "gog_1207658930", null))
        assertNull(adapter.buildLaunchIntent("com.xiaoji.egggame", "local_", null))
        assertNull(adapter.buildLaunchIntent("com.xiaoji.egggame", "-5", null))
        assertNull(adapter.buildLaunchIntent("com.xiaoji.egggame", "0", null))
        assertNull(adapter.buildLaunchIntent("com.xiaoji.egggame", "", null))
    }

    // ── GameNative ────────────────────────────────────────────────────────────

    @Test
    fun `gamenative launches by app_id with a source defaulting to steam`() {
        val adapter = PcLauncherAdapters.forType(PcLauncherType.GAMENATIVE)!!
        val intent = adapter.buildLaunchIntent("app.gamenative", "1984270", null)!!
        assertEquals("app.gamenative.LAUNCH_GAME", intent.action)
        assertEquals("app.gamenative.MainActivity", intent.component?.className)
        assertEquals(1984270, intent.getIntExtra("app_id", -1))
        assertEquals("STEAM", intent.getStringExtra("game_source"))
    }

    // ── No-contract launchers ─────────────────────────────────────────────────

    @Test
    fun `winlator and manual have no id adapter`() {
        assertNull(PcLauncherAdapters.forType(PcLauncherType.WINLATOR))
        assertNull(PcLauncherAdapters.forType(PcLauncherType.MANUAL))
    }
}
