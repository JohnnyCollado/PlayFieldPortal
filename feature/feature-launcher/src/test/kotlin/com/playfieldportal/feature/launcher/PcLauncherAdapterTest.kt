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

    @Test
    fun `pm-less resolution assumes v6`() {
        val intent = PcLauncherAdapters.forType(PcLauncherType.BANNERHUB_V6)!!
            .buildLaunchIntent("banner.hub", "86019", null)!!
        assertEquals(PcLauncherCatalog.V6_DEEP_LINK_ACTIVITY, intent.component?.className)
    }

    @Test
    fun `non-numeric and non-positive ids are rejected`() {
        val adapter = PcLauncherAdapters.forType(PcLauncherType.GAMEHUB_LITE)!!
        assertNull(adapter.buildLaunchIntent("com.xiaoji.egggame", "local_3d55fbbd", null))
        assertNull(adapter.buildLaunchIntent("com.xiaoji.egggame", "-5", null))
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
