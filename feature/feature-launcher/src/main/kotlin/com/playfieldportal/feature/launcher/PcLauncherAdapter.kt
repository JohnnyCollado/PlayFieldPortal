package com.playfieldportal.feature.launcher

import android.content.ComponentName
import android.content.Intent

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
    /** The id must be a positive integer (text ids like `gog_…` are rejected). */
    val requiresIntegerId: Boolean
    /** Optional game-source choices (e.g. GameNative's STEAM/EPIC/GOG/AMAZON); empty = none. */
    val sources: List<String>
    /** Builds the launch intent for one game id, or null if the id is invalid for this launcher. */
    fun buildLaunchIntent(packageName: String, gameId: String, source: String?): Intent?
}

// XiaoJi GameHub family (BannerHub v6, GameHub Lite and forks). The deep-link dispatcher class is
// constant across variants; the action is variant-scoped (`<pkg>.LAUNCH_GAME`). Mirrors the
// documented Beacon/ES-DE recipe: -n <pkg>/com.xiaoji.egggame.DeepLinkActivity
//   -a <pkg>.LAUNCH_GAME --es localGameId <id> --ez autoStartGame true
private class GameHubFamilyAdapter(override val type: PcLauncherType) : PcLauncherAdapter {
    override val idPrompt = "Game ID — in the launcher: open the game → ⋮ → Banner Tools → Show Game ID"
    override val requiresIntegerId = true
    override val sources = emptyList<String>()

    override fun buildLaunchIntent(packageName: String, gameId: String, source: String?): Intent? {
        val id = gameId.trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
        return Intent().apply {
            component = ComponentName(packageName, DEEP_LINK_ACTIVITY)
            action = "$packageName.LAUNCH_GAME"
            // A Steam title (from a .steam export) launches by steamAppId; a BannerHub Local Game ID
            // (add-by-ID, no source) launches by localGameId.
            if (source == "STEAM") putExtra("steamAppId", id.toString())
            else putExtra("localGameId", id.toString())
            putExtra("autoStartGame", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private companion object {
        const val DEEP_LINK_ACTIVITY = "com.xiaoji.egggame.DeepLinkActivity"
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
    fun forType(type: PcLauncherType): PcLauncherAdapter? = when (type) {
        PcLauncherType.BANNERHUB_V6,
        PcLauncherType.GAMEHUB_LITE -> GameHubFamilyAdapter(type)
        PcLauncherType.GAMENATIVE   -> GameNativeAdapter()
        // Winlator launches by shortcut path (.desktop), handled outside the id-based adapter.
        PcLauncherType.WINLATOR,
        PcLauncherType.MANUAL       -> null
    }

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
