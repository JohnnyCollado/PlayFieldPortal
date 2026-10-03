package com.playfieldportal.feature.launcher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unknown Windows Emulators (setup-wizard plan section 6): who is listed, what a user choice
 * does to identity, and how the value cycles.
 */
class UnknownLauncherRulesTest {

    private fun reason(
        pkg: String,
        label: String?,
        identity: LauncherIdentity = LauncherIdentity.NotLauncher,
    ) = PcLauncherCatalog.unknownReason(packageName = pkg, label = label, identity = identity)

    // ── Listing rules ─────────────────────────────────────────────────────────

    @Test
    fun `an ambiguous egggame could be GameHub or BannerHub`() {
        assertEquals("could be GameHub or BannerHub", reason("com.xiaoji.egggame", "GameHub", LauncherIdentity.Ambiguous))
    }

    @Test
    fun `an ambiguous spoof-name variant could be GameHub Lite or BannerHub`() {
        assertEquals(
            "could be GameHub Lite or BannerHub",
            reason("com.ludashi.aibench", "AI Bench", LauncherIdentity.Ambiguous),
        )
    }

    @Test
    fun `uncatalogued apps named like a Windows emulator are listed`() {
        listOf(
            "com.example.winlatorfork" to "My Fork",
            "org.wine.android" to "Wine",
            "com.example.box" to "Box64Droid",
            "com.example.mo" to "Mobox",
            "com.example.gh" to "GameHub Pro",
            "com.example.bh" to "BannerHub Nightly",
        ).forEach { (pkg, label) ->
            assertEquals("not in PFP's list", reason(pkg, label), pkg)
        }
    }

    @Test
    fun `confidently identified launchers are never listed`() {
        assertNull(reason("com.xiaoji.egggame", "GameHub", LauncherIdentity.Known(PcLauncherType.GAMEHUB)))
        assertNull(reason("com.winlator.cmod", "Winlator Cmod", LauncherIdentity.Known(PcLauncherType.WINLATOR)))
        assertNull(reason("gamehub.lite", "GameHub Lite", LauncherIdentity.Known(PcLauncherType.GAMEHUB_LITE)))
    }

    @Test
    fun `the genuine spoof-name apps are never listed`() {
        // Catalog packages whose fingerprint failed: AnTuTu, PUBG, Genshin, CrossFire.
        assertNull(reason("com.antutu.ABenchMark", "AnTuTu Benchmark"))
        assertNull(reason("com.tencent.ig", "PUBG MOBILE"))
        assertNull(reason("com.miHoYo.GenshinImpact", "Genshin Impact"))
        assertNull(reason("com.tencent.tmgp.cf", "CrossFire"))
    }

    @Test
    fun `ordinary apps are not listed`() {
        assertNull(reason("com.android.chrome", "Chrome"))
        assertNull(reason("org.ppsspp.ppssppgold", "PPSSPP Gold"))
    }

    // ── A choice outranks every rule ───────────────────────────────────────────

    @Test
    fun `no choice keeps the automatic identity`() {
        assertEquals(LauncherIdentity.Ambiguous, PcLauncherCatalog.resolveWithChoice(null, LauncherIdentity.Ambiguous))
    }

    @Test
    fun `a launcher choice names the brand even over a confident rule`() {
        assertEquals(
            LauncherIdentity.Known(PcLauncherType.BANNERHUB_V6),
            PcLauncherCatalog.resolveWithChoice(LauncherChoice.BANNERHUB, LauncherIdentity.Known(PcLauncherType.GAMEHUB)),
        )
        assertEquals(
            LauncherIdentity.Known(PcLauncherType.WINLATOR),
            PcLauncherCatalog.resolveWithChoice(LauncherChoice.WINLATOR, LauncherIdentity.NotLauncher),
        )
    }

    @Test
    fun `Not a launcher overrides even a verified install`() {
        assertEquals(
            LauncherIdentity.NotLauncher,
            PcLauncherCatalog.resolveWithChoice(LauncherChoice.NOT_A_LAUNCHER, LauncherIdentity.Known(PcLauncherType.GAMEHUB_LITE)),
        )
    }

    // ── Cycle order ───────────────────────────────────────────────────────────

    @Test
    fun `the value cycles in plan order and wraps`() {
        val seen = generateSequence(LauncherChoice.next(null)) { LauncherChoice.next(it) }.take(7).toList()
        assertEquals(
            listOf(
                LauncherChoice.NOT_A_LAUNCHER, LauncherChoice.GAMEHUB, LauncherChoice.GAMEHUB_LITE,
                LauncherChoice.BANNERHUB, LauncherChoice.WINLATOR, LauncherChoice.GAMENATIVE,
                LauncherChoice.NOT_A_LAUNCHER,
            ),
            seen,
        )
    }

    @Test
    fun `choice labels read like the design`() {
        assertEquals(
            listOf("Not a launcher", "GameHub", "GameHub Lite", "BannerHub", "Winlator", "GameNative"),
            LauncherChoice.entries.map { it.label },
        )
    }
}
