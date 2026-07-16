package com.playfieldportal.feature.launcher

import android.content.ComponentName
import android.content.pm.PackageManager
import java.util.concurrent.ConcurrentHashMap

/** A supported PC-game launcher app PFP can import games from. */
enum class PcLauncherType { WINLATOR, GAMEHUB_LITE, BANNERHUB_V6, GAMENATIVE, MANUAL }

/**
 * The GameHub-family architecture generation, which decides the app's external launch contract
 * (docs/windows-library-refactor-plan.md section 4, manifest-verified 2026-07-16):
 * V6 routes through `com.xiaoji.egggame.DeepLinkActivity`; V5 exposes an exported
 * `com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity` behind the same
 * `<pkg>.LAUNCH_GAME` action pattern.
 */
enum class GameHubGeneration { V5, V6 }

data class PcLauncherDef(
    val type: PcLauncherType,
    val displayName: String,
    // Known package names for this launcher (official + common forks). GameHub-family entries
    // share the spoof pool below, so a package match alone is NOT proof of install — use
    // [PcLauncherCatalog.verifiedInstalledPackage], which fingerprints the app's components.
    val packageNames: List<String>,
)

/**
 * Curated catalog of PC launchers the Import PC Games flow recognises. PFP is a frontend for
 * these apps, never the PC runtime: imported entries launch back into the source launcher.
 */
object PcLauncherCatalog {

    // GameHub Lite and BannerHub ReVanced release variants under spoofed package names that
    // manufacturers whitelist for performance modes. CRITICAL: the spoofed names are the GENUINE
    // package names of real apps (AnTuTu, PUBG Mobile, Genshin Impact, CrossFire) — a package
    // match must be confirmed by the component fingerprint before treating the install as a
    // launcher. Launcher-owned names first so detection prefers them.
    private val GAMEHUB_FAMILY_PACKAGES = listOf(
        "gamehub.lite",                // GameHub Lite base / BannerHub Normal-GHL
        "banner.hub",                  // BannerHub Normal
        "com.xiaoji.egggame",          // upstream GameHub / BannerHub Original
        "com.antutu.ABenchMark",       // AnTuTu variant
        "com.antutu.benchmark.full",   // alt-AnTuTu variant
        "com.ludashi.aibench",         // Ludashi variant
        "com.tencent.ig",              // PUBG variant
        "com.miHoYo.GenshinImpact",    // Genshin variant (BannerHub)
        "com.tencent.tmgp.cf",         // PuBG-CrossFire variant (BannerHub)
    )

    // Lineage marker activities — the classes are constant across variant package names, so
    // their presence both proves "this is really a GameHub-family launcher" (the genuine
    // AnTuTu/PUBG/Genshin contain neither) and picks the launch contract generation.
    const val V6_DEEP_LINK_ACTIVITY = "com.xiaoji.egggame.DeepLinkActivity"
    const val V5_GAME_DETAIL_ACTIVITY = "com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity"
    private const val V5_ROUTER_ACTIVITY = "com.xj.app.DeepLinkRouterActivity"

    val entries: List<PcLauncherDef> = listOf(
        PcLauncherDef(PcLauncherType.BANNERHUB_V6, "BannerHub",    GAMEHUB_FAMILY_PACKAGES),
        PcLauncherDef(PcLauncherType.GAMEHUB_LITE, "GameHub Lite", GAMEHUB_FAMILY_PACKAGES),
        PcLauncherDef(PcLauncherType.WINLATOR,     "Winlator",     listOf("com.winlator", "com.winlator.cmod")),
        PcLauncherDef(PcLauncherType.GAMENATIVE,   "GameNative",   listOf("app.gamenative")),
    )

    // First definition claiming a package. GameHub-family packages resolve to BannerHub here;
    // display-name-only ambiguity — both family launchers share one launch adapter, so the
    // built intent is identical either way.
    private val byPackage: Map<String, PcLauncherDef> =
        entries.flatMap { def -> def.packageNames.map { it to def } }.toMap()

    fun forPackage(packageName: String?): PcLauncherDef? = packageName?.let { byPackage[it] }

    /** True when [packageName] is in the shared GameHub-family pool (fingerprint still required). */
    fun isGameHubFamilyPackage(packageName: String?): Boolean =
        packageName in GAMEHUB_FAMILY_PACKAGES

    // ── Component fingerprint ─────────────────────────────────────────────────

    private data class CachedFingerprint(val lastUpdateTime: Long, val generation: GameHubGeneration?)

    private val fingerprints = ConcurrentHashMap<String, CachedFingerprint>()

    /**
     * The installed package's GameHub generation, or null when the package is absent or is NOT a
     * GameHub-family launcher (e.g. the genuine app whose name a variant spoofs). Cached per
     * package and refreshed when the install's `lastUpdateTime` changes.
     */
    fun gameHubGeneration(packageName: String, pm: PackageManager): GameHubGeneration? {
        val info = runCatching { pm.getPackageInfo(packageName, 0) }.getOrNull() ?: return null
        fingerprints[packageName]
            ?.takeIf { it.lastUpdateTime == info.lastUpdateTime }
            ?.let { return it.generation }
        val generation = resolveGeneration(
            versionNameMajor = info.versionName?.substringBefore('.')?.toIntOrNull(),
            label            = applicationLabel(packageName, pm),
            hasClass         = { cls -> hasActivity(packageName, cls, pm) },
        )
        fingerprints[packageName] = CachedFingerprint(info.lastUpdateTime, generation)
        return generation
    }

    // Signals in order of strength (plan section 4): lineage components decide; a family-sounding
    // label plus the versionName major is the fallback for future variants that relocate their
    // classes; anything else is the spoofed-name genuine app and resolves to null.
    internal fun resolveGeneration(
        versionNameMajor: Int?,
        label: String?,
        hasClass: (String) -> Boolean,
    ): GameHubGeneration? = when {
        hasClass(V6_DEEP_LINK_ACTIVITY) -> GameHubGeneration.V6
        hasClass(V5_GAME_DETAIL_ACTIVITY) || hasClass(V5_ROUTER_ACTIVITY) -> GameHubGeneration.V5
        labelNamesFamilyLauncher(label) ->
            if ((versionNameMajor ?: 0) >= 6) GameHubGeneration.V6 else GameHubGeneration.V5
        else -> null
    }

    // ── Verification ──────────────────────────────────────────────────────────

    /**
     * The first installed package that verifiably belongs to [def]. GameHub-family packages must
     * pass the component fingerprint — so the real AnTuTu/PUBG/Genshin/CrossFire never register
     * as an installed PC launcher — and the label then only settles WHICH brand name claims the
     * install (BannerHub rows take banner-labeled installs, GameHub Lite takes the rest).
     * Winlator/GameNative packages are launcher-exclusive and need no fingerprint.
     */
    fun verifiedInstalledPackage(def: PcLauncherDef, pm: PackageManager): String? =
        def.packageNames.firstOrNull { pkg -> verifiesAs(def.type, pkg, pm) }

    /**
     * True when [packageName] is a launcher PFP trusts as a PC-game source: a catalog package
     * that, for GameHub-family names, also passes the component fingerprint. The gate for
     * shortcut routing (plan section 3).
     */
    fun isVerifiedPcLauncher(packageName: String?, pm: PackageManager): Boolean {
        val pkg = packageName ?: return false
        if (forPackage(pkg) == null) return false
        return if (pkg in GAMEHUB_FAMILY_PACKAGES) gameHubGeneration(pkg, pm) != null else true
    }

    /** Every installed GameHub-family package whose components confirm a launcher (variants can
     *  be installed side-by-side — that's the point of the variant scheme). */
    fun installedGameHubFamilyPackages(pm: PackageManager): List<String> =
        GAMEHUB_FAMILY_PACKAGES.filter { gameHubGeneration(it, pm) != null }

    private fun verifiesAs(type: PcLauncherType, pkg: String, pm: PackageManager): Boolean {
        if (pkg !in GAMEHUB_FAMILY_PACKAGES) return isInstalled(pkg, pm)
        if (gameHubGeneration(pkg, pm) == null) return false
        return brandMatches(type, applicationLabel(pkg, pm))
    }

    // Label decides display branding only, never launcher-hood: BannerHub claims banner-labeled
    // installs, GameHub Lite claims every other fingerprint-verified variant (a rebrand with an
    // arbitrary label still verifies — live case: the Ludashi variant).
    internal fun brandMatches(type: PcLauncherType, label: String?): Boolean {
        val isBanner = label?.contains("banner", ignoreCase = true) == true
        return when (type) {
            PcLauncherType.BANNERHUB_V6 -> isBanner
            PcLauncherType.GAMEHUB_LITE -> !isBanner
            else                        -> true
        }
    }

    private fun labelNamesFamilyLauncher(label: String?): Boolean =
        label != null && (
            label.contains("gamehub", ignoreCase = true) ||
                label.contains("game hub", ignoreCase = true) ||
                label.contains("banner", ignoreCase = true)
            )

    private fun isInstalled(pkg: String, pm: PackageManager): Boolean =
        runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess

    private fun applicationLabel(pkg: String, pm: PackageManager): String? = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    private fun hasActivity(pkg: String, cls: String, pm: PackageManager): Boolean =
        runCatching { pm.getActivityInfo(ComponentName(pkg, cls), 0) }.isSuccess
}
