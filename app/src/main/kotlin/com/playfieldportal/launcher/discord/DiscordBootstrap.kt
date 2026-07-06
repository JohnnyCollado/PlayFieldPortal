package com.playfieldportal.launcher.discord

import androidx.activity.ComponentActivity

/**
 * The app's only entry point into the optional Discord integration, so `MainActivity` carries no
 * dependency on the native SDK. Bound per product flavor via Hilt: the **full** flavor attaches the
 * SDK engine and restores/updates the session; the **lite** flavor (SDK excluded to shrink the
 * download) binds a no-op.
 */
interface DiscordBootstrap {
    /** App launch: attach the SDK engine and restore a saved session (full) / do nothing (lite). */
    fun onCreate(activity: ComponentActivity)

    /** Returning to the foreground: drop the per-game presence back to idle (full) / nothing (lite). */
    fun onResume()
}
