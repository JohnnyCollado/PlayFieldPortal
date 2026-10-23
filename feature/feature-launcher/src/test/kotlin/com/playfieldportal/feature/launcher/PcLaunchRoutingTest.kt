package com.playfieldportal.feature.launcher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Launch With for imported Windows games: which installed launchers can open a game, decided by
 * the store ids it is known by. A game's own launcher always can; another launcher only when it
 * addresses games by an id this game has — Winlator-family launchers never do, their games live
 * as .desktop files inside the app.
 */
class PcLaunchRoutingTest {

    private val gameNative = InstalledPcLauncher("app.gamenative", PcLauncherType.GAMENATIVE, "GameNative")
    private val bannerHub = InstalledPcLauncher("banner.hub", PcLauncherType.BANNERHUB_V6, "BannerHub")
    private val winlator = InstalledPcLauncher("com.winlator.cmod", PcLauncherType.WINLATOR, "Winlator Cmod")
    private val bannerlator = InstalledPcLauncher("com.winlator.banner", PcLauncherType.WINLATOR, "Bannerlator")
    private val installed = listOf(gameNative, bannerHub, winlator, bannerlator)

    private val steam = PcStoreId("STEAM", "1173820")

    private fun options(own: String, ids: List<PcStoreId>, sharedDesktop: Boolean = false) =
        PcLaunchRouting.options(
            ownPackage = own, ownLabel = "Own", installed = installed, ids = ids, sharedDesktop = sharedDesktop,
        )

    @Test
    fun `the game's own launcher is always first and marked original`() {
        val opts = options("com.winlator.banner", emptyList())
        assertEquals(listOf(PcLaunchOption("com.winlator.banner", "Own", isOriginal = true)), opts)
    }

    @Test
    fun `a Steam game can also open in GameNative and the GameHub family, never Winlator`() {
        val opts = options("com.winlator.banner", listOf(steam)).map { it.packageName }
        assertEquals(listOf("com.winlator.banner", "app.gamenative", "banner.hub"), opts)
    }

    @Test
    fun `the own launcher is not listed twice`() {
        val opts = options("app.gamenative", listOf(steam)).map { it.packageName }
        assertEquals(listOf("app.gamenative", "banner.hub"), opts)
    }

    @Test
    fun `GameNative takes its other stores, the GameHub family only Steam`() {
        val gog = PcStoreId("GOG", "1207658924")
        assertEquals(gog, PcLaunchRouting.storeIdFor(PcLauncherType.GAMENATIVE, listOf(gog)))
        assertNull(PcLaunchRouting.storeIdFor(PcLauncherType.BANNERHUB_V6, listOf(gog)))
        assertEquals(steam, PcLaunchRouting.storeIdFor(PcLauncherType.GAMEHUB_LITE, listOf(gog, steam)))
    }

    @Test
    fun `an id a launcher cannot parse opens nothing`() {
        // CUSTOM_GAME ids and non-numeric ids are launcher-internal, not a store's.
        val ids = listOf(PcStoreId("CUSTOM_GAME", "42"), PcStoreId("STEAM", "local_ab12"), PcStoreId("STEAM", "0"))
        assertNull(PcLaunchRouting.storeIdFor(PcLauncherType.GAMENATIVE, ids))
        assertNull(PcLaunchRouting.storeIdFor(PcLauncherType.GAMEHUB, ids))
    }

    @Test
    fun `Winlator-family launchers never open a game by id`() {
        assertNull(PcLaunchRouting.storeIdFor(PcLauncherType.WINLATOR, listOf(steam)))
        assertNull(PcLaunchRouting.storeIdFor(PcLauncherType.MANUAL, listOf(steam)))
    }

    @Test
    fun `a shared desktop export can run in any installed Winlator, and only those`() {
        val opts = options("com.winlator.banner", emptyList(), sharedDesktop = true).map { it.packageName }
        assertEquals(listOf("com.winlator.banner", "com.winlator.cmod"), opts)
    }

    @Test
    fun `a shared desktop export with a Steam id adds the id launchers too`() {
        val opts = options("com.winlator.cmod", listOf(steam), sharedDesktop = true).map { it.packageName }
        assertEquals(listOf("com.winlator.cmod", "app.gamenative", "banner.hub", "com.winlator.banner"), opts)
    }

    // ── Launch With's value (survey U15/U29, docs/audits/codebase-survey-2026-10-22.md) ──────
    // It named the chosen launcher even when the launch fell back to the game's own, and it was
    // shown with nothing else to choose.

    private val own = PcLaunchOption("app.gamenative", "GameNative", isOriginal = true)
    private val other = PcLaunchOption("banner.hub", "BannerHub", isOriginal = false)

    @Test
    fun `the label names the chosen launcher while it can still open the game`() {
        assertEquals("BannerHub", PcLaunchRouting.launchWithLabel("banner.hub", listOf(own, other)))
    }

    @Test
    fun `a chosen launcher that can no longer open the game reads as the game's own`() {
        assertEquals("GameNative", PcLaunchRouting.launchWithLabel("com.uninstalled", listOf(own, other)))
        assertEquals("GameNative", PcLaunchRouting.launchWithLabel(null, listOf(own, other)))
    }

    @Test
    fun `with nothing else to choose there is no Launch With row`() {
        assertNull(PcLaunchRouting.launchWithLabel(null, listOf(own)))
        assertNull(PcLaunchRouting.launchWithLabel(null, emptyList()))
    }
}
