package com.playfieldportal.feature.launcher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure fingerprint/brand logic, pinned to the live-device evidence of 2026-07-16:
 * xiaoji 6.0.9 carries `com.xiaoji.egggame.DeepLinkActivity`; the Ludashi 5.1.7 variant carries
 * the `com.xj.*` classes instead; the genuine spoofed-name apps carry neither.
 */
class PcLauncherCatalogTest {

    private fun classesOf(vararg present: String): (String) -> Boolean = { it in present.toSet() }

    // ── resolveGeneration ─────────────────────────────────────────────────────

    @Test
    fun `v6 lineage component wins`() {
        assertEquals(
            GameHubGeneration.V6,
            PcLauncherCatalog.resolveGeneration(
                versionNameMajor = 6,
                label = "GameHub",
                hasClass = classesOf(PcLauncherCatalog.V6_DEEP_LINK_ACTIVITY),
            ),
        )
    }

    @Test
    fun `v5 lineage components win, even against a v6-looking version`() {
        assertEquals(
            GameHubGeneration.V5,
            PcLauncherCatalog.resolveGeneration(
                versionNameMajor = 6,
                label = null,
                hasClass = classesOf(PcLauncherCatalog.V5_GAME_DETAIL_ACTIVITY),
            ),
        )
        assertEquals(
            GameHubGeneration.V5,
            PcLauncherCatalog.resolveGeneration(
                versionNameMajor = null,
                label = null,
                hasClass = classesOf("com.xj.app.DeepLinkRouterActivity"),
            ),
        )
    }

    @Test
    fun `component beats version when both lineages would disagree`() {
        // A v5-major install carrying the v6 dispatcher resolves by its components, not its label
        // or version string.
        assertEquals(
            GameHubGeneration.V6,
            PcLauncherCatalog.resolveGeneration(
                versionNameMajor = 5,
                label = "BannerHub",
                hasClass = classesOf(PcLauncherCatalog.V6_DEEP_LINK_ACTIVITY),
            ),
        )
    }

    @Test
    fun `family-labeled install without known components falls back to version major`() {
        assertEquals(
            GameHubGeneration.V6,
            PcLauncherCatalog.resolveGeneration(versionNameMajor = 6, label = "GameHub Pro") { false },
        )
        assertEquals(
            GameHubGeneration.V5,
            PcLauncherCatalog.resolveGeneration(versionNameMajor = 5, label = "BannerHub") { false },
        )
    }

    @Test
    fun `spoofed-name genuine app resolves to null`() {
        // The real AnTuTu: no lineage components, no launcher-naming label.
        assertNull(
            PcLauncherCatalog.resolveGeneration(versionNameMajor = 10, label = "AnTuTu Benchmark") { false },
        )
        assertNull(PcLauncherCatalog.resolveGeneration(versionNameMajor = null, label = null) { false })
    }

    // ── catalog lookups ───────────────────────────────────────────────────────

    @Test
    fun `family pool membership is unchanged by the fingerprint rework`() {
        assertTrue(PcLauncherCatalog.isGameHubFamilyPackage("com.ludashi.aibench"))
        assertTrue(PcLauncherCatalog.isGameHubFamilyPackage("com.xiaoji.egggame"))
        assertFalse(PcLauncherCatalog.isGameHubFamilyPackage("app.gamenative"))
        assertFalse(PcLauncherCatalog.isGameHubFamilyPackage(null))
    }

    @Test
    fun `launcher-exclusive packages resolve without a fingerprint`() {
        assertEquals(PcLauncherType.GAMENATIVE, PcLauncherCatalog.forPackage("app.gamenative")?.type)
        assertEquals(PcLauncherType.WINLATOR, PcLauncherCatalog.forPackage("com.winlator")?.type)
        assertNull(PcLauncherCatalog.forPackage("com.example.random"))
    }

    @Test
    fun `Winlator Ludashi packages are claimed by the Winlator launcher`() {
        // Odin3 evidence: com.winlator.vanilla is labelled "Winlator Ludashi"; the Obtainium pack
        // publishes it as com.winlator.ludashi.
        assertEquals(PcLauncherType.WINLATOR, PcLauncherCatalog.forPackage("com.winlator.ludashi")?.type)
        assertEquals(PcLauncherType.WINLATOR, PcLauncherCatalog.forPackage("com.winlator.vanilla")?.type)
        assertFalse(PcLauncherCatalog.isGameHubFamilyPackage("com.winlator.vanilla"))
    }

    // ── resolveIdentity (plan section 5) ──────────────────────────────────────

    private val gamesir = PcLauncherCatalog.GAMESIR_SIGNER_SHA256
    private val aospTestkey = "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc"
    private val liteBuilderKey = "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0"

    private fun identity(signer: String?, label: String?, pkg: String, gen: GameHubGeneration? = GameHubGeneration.V6) =
        PcLauncherCatalog.resolveIdentity(signerSha256 = signer, label = label, packageName = pkg, generation = gen)

    @Test
    fun `a failed fingerprint is never a launcher, whatever the label or signer says`() {
        // The genuine apps a variant spoofs: AnTuTu, PUBG, Genshin, CrossFire.
        listOf("com.antutu.ABenchMark", "com.tencent.ig", "com.miHoYo.GenshinImpact", "com.tencent.tmgp.cf")
            .forEach { pkg ->
                assertEquals(LauncherIdentity.NotLauncher, identity(aospTestkey, "BannerHub", pkg, gen = null), pkg)
            }
        assertEquals(LauncherIdentity.NotLauncher, identity(gamesir, "GameHub", "com.xiaoji.egggame", gen = null))
    }

    @Test
    fun `the Odin3 egggame signed by GameSir is the official GameHub`() {
        assertEquals(
            LauncherIdentity.Known(PcLauncherType.GAMEHUB),
            identity(gamesir, "GameHub", "com.xiaoji.egggame"),
        )
    }

    @Test
    fun `GameSir's signature outranks a label`() {
        // A repack cannot carry GameSir's key, so the signer decides before any label rule.
        assertEquals(
            LauncherIdentity.Known(PcLauncherType.GAMEHUB),
            identity(gamesir, "GameHub Lite", "gamehub.lite"),
        )
    }

    @Test
    fun `BannerHub variants resolve by label on every shared package id`() {
        // BannerHub labels every variant "BannerHub …" and signs with the AOSP testkey.
        listOf(
            "com.xiaoji.egggame" to "BannerHub Original",
            "gamehub.lite" to "BannerHub Normal.GHL",
            "banner.hub" to "BannerHub",
            "com.antutu.ABenchMark" to "BannerHub AnTuTu",
            "com.antutu.benchmark.full" to "BannerHub Alt-AnTuTu",
            "com.ludashi.aibench" to "BannerHub Ludashi",
            "com.tencent.ig" to "BannerHub PuBG",
            "com.miHoYo.GenshinImpact" to "BannerHub Genshin",
            "com.tencent.tmgp.cf" to "BannerHub PuBG-CrossFire",
        ).forEach { (pkg, label) ->
            assertEquals(LauncherIdentity.Known(PcLauncherType.BANNERHUB_V6), identity(aospTestkey, label, pkg), pkg)
        }
    }

    @Test
    fun `GameHub Lite resolves by its label on the shared variant ids`() {
        listOf(
            "gamehub.lite",
            "com.antutu.ABenchMark",
            "com.antutu.benchmark.full",
            "com.ludashi.aibench",
            "com.tencent.ig",
        ).forEach { pkg ->
            assertEquals(
                LauncherIdentity.Known(PcLauncherType.GAMEHUB_LITE),
                identity(liteBuilderKey, "GameHub Lite", pkg, GameHubGeneration.V5),
                pkg,
            )
        }
    }

    @Test
    fun `gamehub dot lite with a non-BannerHub label is GameHub Lite`() {
        assertEquals(
            LauncherIdentity.Known(PcLauncherType.GAMEHUB_LITE),
            identity(liteBuilderKey, "GameHub", "gamehub.lite", GameHubGeneration.V5),
        )
    }

    @Test
    fun `a verified install no rule settles is ambiguous`() {
        // Live case: the Ludashi variant whose label names neither brand.
        assertEquals(LauncherIdentity.Ambiguous, identity(liteBuilderKey, "AI Bench", "com.ludashi.aibench", GameHubGeneration.V5))
        // egggame labelled GameHub but not signed by GameSir: GameHub or a BannerHub v6 repack.
        assertEquals(LauncherIdentity.Ambiguous, identity(aospTestkey, "GameHub", "com.xiaoji.egggame"))
        // Unreadable signer and label.
        assertEquals(LauncherIdentity.Ambiguous, identity(null, null, "com.xiaoji.egggame"))
    }

    @Test
    fun `brand label matching ignores case`() {
        assertEquals(
            LauncherIdentity.Known(PcLauncherType.BANNERHUB_V6),
            identity(aospTestkey, "BANNERHUB original", "com.xiaoji.egggame"),
        )
        assertEquals(
            LauncherIdentity.Known(PcLauncherType.GAMEHUB_LITE),
            identity(liteBuilderKey, "gamehub LITE", "com.tencent.ig"),
        )
    }

    @Test
    fun `GameHub is a GameHub-family type sharing the family launch adapter`() {
        assertTrue(PcLauncherType.GAMEHUB.isGameHubFamily)
        assertTrue(PcLauncherType.GAMEHUB_LITE.isGameHubFamily)
        assertTrue(PcLauncherType.BANNERHUB_V6.isGameHubFamily)
        assertFalse(PcLauncherType.WINLATOR.isGameHubFamily)
        assertTrue(PcLauncherAdapters.forType(PcLauncherType.GAMEHUB) != null)
    }

    @Test
    fun `every GameHub-family brand has a catalog entry on the shared pool`() {
        listOf(PcLauncherType.GAMEHUB, PcLauncherType.GAMEHUB_LITE, PcLauncherType.BANNERHUB_V6).forEach { type ->
            val def = PcLauncherCatalog.entries.single { it.type == type }
            assertTrue("com.xiaoji.egggame" in def.packageNames, type.name)
        }
    }
}
