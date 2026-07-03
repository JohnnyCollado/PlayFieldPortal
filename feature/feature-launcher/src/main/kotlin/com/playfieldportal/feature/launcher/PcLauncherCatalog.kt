package com.playfieldportal.feature.launcher

/** A supported PC-game launcher app PFP can import games from. */
enum class PcLauncherType { WINLATOR, GAMEHUB_LITE, BANNERHUB_V6, GAMENATIVE, MANUAL }

data class PcLauncherDef(
    val type: PcLauncherType,
    val displayName: String,
    // Known package names for this launcher (official + common forks). Package detection only —
    // per-launcher scan/launch adapters are layered on top as their source contracts are confirmed.
    val packageNames: List<String>,
)

/**
 * Curated catalog of PC launchers the Import PC Games flow recognises. PFP is a frontend for
 * these apps, never the PC runtime: imported entries launch back into the source launcher.
 */
object PcLauncherCatalog {

    val entries: List<PcLauncherDef> = listOf(
        PcLauncherDef(PcLauncherType.BANNERHUB_V6, "BannerHub",    listOf("com.xiaoji.egggame")),
        PcLauncherDef(PcLauncherType.GAMEHUB_LITE, "GameHub Lite", listOf("gamehub.lite")),
        PcLauncherDef(PcLauncherType.WINLATOR,     "Winlator",     listOf("com.winlator", "com.winlator.cmod")),
        PcLauncherDef(PcLauncherType.GAMENATIVE,   "GameNative",   listOf("app.gamenative")),
    )

    private val byPackage: Map<String, PcLauncherDef> =
        entries.flatMap { def -> def.packageNames.map { it to def } }.toMap()

    fun forPackage(packageName: String?): PcLauncherDef? = packageName?.let { byPackage[it] }
}
