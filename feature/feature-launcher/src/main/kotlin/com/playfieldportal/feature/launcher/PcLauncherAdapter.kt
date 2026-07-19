package com.playfieldportal.feature.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Builds the explicit launch [Intent] that boots a specific game in a PC launcher, from a game id
 * the user supplies (the launchers keep their libraries in private databases PFP can't read, but
 * each exposes a documented launch contract). PFP stores the built intent on a normal game row
 * (`launchIntentUri`) and fires it through the existing sanitized launch path — no Home role and no
 * new launch code. Intents are pinned to a concrete component so they resolve to the target app.
 */
interface PcLauncherAdapter {
    val type: PcLauncherType
    /** Hint shown in the Add-by-ID dialog for where to find the id. */
    val idPrompt: String
    /** True when only positive integer ids are accepted (GameHub also takes `local_<uuid>`). */
    val requiresIntegerId: Boolean
    /** Optional game-source choices (e.g. GameNative's STEAM/EPIC/GOG/AMAZON); empty = none. */
    val sources: List<String>
    /** Builds the launch intent for one game id, or null if the id is invalid for this launcher. */
    fun buildLaunchIntent(packageName: String, gameId: String, source: String?): Intent?
}

// XiaoJi GameHub family (BannerHub v6, GameHub Lite and forks). The action is variant-scoped
// (`<pkg>.LAUNCH_GAME`) and the extras naming is shared, but the target component differs by
// architecture generation (manifest-verified 2026-07-16, live-fired on both installed variants;
// V5 id namespaces live-fired on Ludashi 5.1.7, 2026-07-18):
//   V6: -n <pkg>/com.xiaoji.egggame.DeepLinkActivity (the documented Beacon/ES-DE recipe)
//   V5: -n <pkg>/com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity (exported)
// with --es steamAppId|localGameId <id> --ez autoStartGame true either way.
//
// Two id namespaces exist and do not overlap:
//   local_<uuid>  — manually added local EXEs (shown with a Copy button on the game's page);
//                   launches via localGameId with the string passed through verbatim.
//   numeric       — Steam-matched installs address by steamAppId; launcher-local integer ids
//                   (Banner Tools → Show Game ID) address by localGameId. On V5 the wrong
//                   namespace opens an empty "Get Game" stub, so numeric ids are sent as BOTH
//                   extras — the launcher resolves whichever matches (steam wins, live-fired).
//                   V6 keeps localGameId-only, its proven contract.
private class GameHubFamilyAdapter(
    override val type: PcLauncherType,
    private val generationOf: (String) -> GameHubGeneration,
) : PcLauncherAdapter {
    override val idPrompt =
        "Game ID — on the game's page: tap Copy next to Local Game ID, or ⋮ → Banner Tools → Show Game ID"
    override val requiresIntegerId = false
    override val sources = emptyList<String>()

    override fun buildLaunchIntent(packageName: String, gameId: String, source: String?): Intent? {
        val trimmed = gameId.trim()
        val isLocalId = trimmed.startsWith(LOCAL_ID_PREFIX) && trimmed.length > LOCAL_ID_PREFIX.length
        val numericId = trimmed.toIntOrNull()?.takeIf { it > 0 }
        if (!isLocalId && numericId == null) return null
        val generation = generationOf(packageName)
        val activity = when (generation) {
            GameHubGeneration.V6 -> PcLauncherCatalog.V6_DEEP_LINK_ACTIVITY
            GameHubGeneration.V5 -> PcLauncherCatalog.V5_GAME_DETAIL_ACTIVITY
        }
        return Intent().apply {
            component = ComponentName(packageName, activity)
            action = "$packageName.LAUNCH_GAME"
            when {
                // A manually added local game: the launcher's own string id, verbatim.
                isLocalId -> putExtra("localGameId", trimmed)
                // A Steam title (from a .steam export) launches by steamAppId.
                source == "STEAM" -> putExtra("steamAppId", numericId.toString())
                // Add-by-ID numeric on V5: namespace unknown, send both and let the launcher
                // resolve the one that matches.
                generation == GameHubGeneration.V5 -> {
                    putExtra("steamAppId", numericId.toString())
                    putExtra("localGameId", numericId.toString())
                }
                else -> putExtra("localGameId", numericId.toString())
            }
            putExtra("autoStartGame", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private companion object {
        const val LOCAL_ID_PREFIX = "local_"
    }
}

// GameNative (app.gamenative). MainActivity handles `app.gamenative.LAUNCH_GAME` with extras
// app_id (int > 0), game_source (STEAM/EPIC/GOG/AMAZON), optional container_config JSON.
private class GameNativeAdapter : PcLauncherAdapter {
    override val type = PcLauncherType.GAMENATIVE
    override val idPrompt = "Steam App ID (or store app id) for the installed game"
    override val requiresIntegerId = true
    override val sources = listOf("STEAM", "EPIC", "GOG", "AMAZON")

    override fun buildLaunchIntent(packageName: String, gameId: String, source: String?): Intent? {
        val id = gameId.trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
        return Intent().apply {
            component = ComponentName(packageName, "app.gamenative.MainActivity")
            action = "$packageName.LAUNCH_GAME"
            putExtra("app_id", id)
            putExtra("game_source", source?.takeIf { it.isNotBlank() } ?: "STEAM")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

/** Resolves the add-by-ID adapter for a launcher, or null when it has no external launch contract. */
object PcLauncherAdapters {
    /**
     * [pm] lets the GameHub-family adapter fingerprint the target package's generation at build
     * time; without it (UI metadata like id prompts and `canAddById` checks) V6 is assumed.
     */
    fun forType(type: PcLauncherType, pm: PackageManager? = null): PcLauncherAdapter? = when (type) {
        PcLauncherType.BANNERHUB_V6,
        PcLauncherType.GAMEHUB_LITE -> GameHubFamilyAdapter(type) { pkg ->
            pm?.let { PcLauncherCatalog.gameHubGeneration(pkg, it) } ?: GameHubGeneration.V6
        }
        PcLauncherType.GAMENATIVE   -> GameNativeAdapter()
        // Winlator launches by shortcut path (.desktop), handled outside the id-based adapter.
        PcLauncherType.WINLATOR,
        PcLauncherType.MANUAL       -> null
    }

    /** Test seam: a GameHub-family adapter with the generation fingerprint pinned. */
    internal fun gameHubAdapterFor(
        type: PcLauncherType,
        generationOf: (String) -> GameHubGeneration,
    ): PcLauncherAdapter = GameHubFamilyAdapter(type, generationOf)

    // GameNative's per-store export extension → its `game_source` value (see FrontendSyncManager).
    fun gameSourceForExtension(extension: String): String? = when (extension.lowercase()) {
        "steam"  -> "STEAM"
        "epic"   -> "EPIC"
        "gog"    -> "GOG"
        "amazon" -> "AMAZON"
        "pcgame" -> "CUSTOM_GAME"
        else     -> null
    }
}
