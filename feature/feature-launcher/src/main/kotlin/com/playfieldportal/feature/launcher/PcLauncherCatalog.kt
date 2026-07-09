package com.playfieldportal.feature.launcher

import android.content.pm.PackageManager

/** A supported PC-game launcher app PFP can import games from. */
enum class PcLauncherType { WINLATOR, GAMEHUB_LITE, BANNERHUB_V6, GAMENATIVE, MANUAL }

data class PcLauncherDef(
    val type: PcLauncherType,
    val displayName: String,
    // Known package names for this launcher (official + common forks). GameHub-family entries
    // share the spoof pool below, so a package match alone is NOT proof of install — use
    // [PcLauncherCatalog.verifiedInstalledPackage], which also checks the app label.
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
    // match must be confirmed by the app label before treating the install as a launcher.
    // Launcher-owned names first so detection prefers them.
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

    /** True when [packageName] is in the shared GameHub-family pool (label check still required). */
    fun isGameHubFamilyPackage(packageName: String?): Boolean =
        packageName in GAMEHUB_FAMILY_PACKAGES

    /**
     * The first installed package that verifiably belongs to [def]. For GameHub-family entries the
     * application label must identify the launcher — "BannerHub" claims BannerHub rows, any other
     * "GameHub" label claims GameHub Lite — so the real AnTuTu/PUBG/Genshin/CrossFire never
     * register as an installed PC launcher. Winlator/GameNative packages are launcher-exclusive
     * and need no label check.
     */
    fun verifiedInstalledPackage(def: PcLauncherDef, pm: PackageManager): String? =
        def.packageNames.firstOrNull { pkg -> installedLabelMatches(def.type, pkg, pm) }

    private fun installedLabelMatches(type: PcLauncherType, pkg: String, pm: PackageManager): Boolean {
        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull() ?: return false   // not installed

        if (pkg !in GAMEHUB_FAMILY_PACKAGES) return true
        val isBanner  = label.contains("banner", ignoreCase = true)
        val isGameHub = label.contains("gamehub", ignoreCase = true) ||
            label.contains("game hub", ignoreCase = true)
        return when (type) {
            PcLauncherType.BANNERHUB_V6 -> isBanner
            PcLauncherType.GAMEHUB_LITE -> isGameHub && !isBanner
            else                        -> isBanner || isGameHub
        }
    }

    /** Every installed GameHub-family package whose label confirms a launcher (variants can be
     *  installed side-by-side — that's the point of the variant scheme). */
    fun installedGameHubFamilyPackages(pm: PackageManager): List<String> =
        GAMEHUB_FAMILY_PACKAGES.filter { pkg ->
            installedLabelMatches(PcLauncherType.MANUAL, pkg, pm)
        }
}
