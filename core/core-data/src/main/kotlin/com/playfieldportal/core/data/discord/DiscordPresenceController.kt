package com.playfieldportal.core.data.discord

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.network.NetworkMonitor
import com.playfieldportal.core.domain.discord.DiscordSessionActivator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the opt-in **Activity sharing** preference and broadcasts it to Discord.
 *
 * Privacy: sharing is **off by default** (§8 of the plan). When on, PFP broadcasts an app-scoped
 * rich presence — the SDK already limits activity to our application, so friends only ever see that
 * you're in Playfield Portal, never anything outside it. While a game is running the presence shows
 * that game's title ("Playing <title>"); back in the launcher it falls back to the app name.
 * **Generic mode** replaces whatever we'd show with a neutral "a game", so even the title is hidden.
 * Nothing here is broadcast until the user opts in.
 */
@Singleton
class DiscordPresenceController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionActivator: DiscordSessionActivator,
    private val networkMonitor: NetworkMonitor,
) {
    private val shareKey = booleanPreferencesKey("discord_share_activity")
    private val genericKey = booleanPreferencesKey("discord_generic_activity")

    /**
     * The game currently in the foreground, or null when we're idle in the launcher. Held in memory
     * only — presence is ephemeral and must never outlive the process. Written from the launch path
     * and cleared when PFP returns to the foreground.
     */
    @Volatile private var currentGame: String? = null

    fun observeShareEnabled(): Flow<Boolean> = context.pfpDataStore.data.map { it[shareKey] ?: false }
    fun observeGenericMode(): Flow<Boolean> = context.pfpDataStore.data.map { it[genericKey] ?: false }

    suspend fun isShareEnabled(): Boolean = context.pfpDataStore.data.first()[shareKey] ?: false
    suspend fun isGenericMode(): Boolean = context.pfpDataStore.data.first()[genericKey] ?: false

    suspend fun setShareEnabled(enabled: Boolean) {
        context.pfpDataStore.edit { it[shareKey] = enabled }
        refresh()
    }

    suspend fun setGenericMode(enabled: Boolean) {
        context.pfpDataStore.edit { it[genericKey] = enabled }
        refresh()
    }

    /**
     * Record the game that just moved to the foreground (title as shown in PFP) and re-broadcast.
     * Pass null to go back to idle. No-ops when the title is unchanged, so it's cheap to call on
     * every foreground transition. The title is sanitized here (untrusted-in, §8) before it can ever
     * reach the wire.
     */
    suspend fun setCurrentGame(title: String?) {
        val clean = title?.let(::sanitizeTitle)?.takeIf { it.isNotBlank() }
        if (clean == currentGame) return
        currentGame = clean
        refresh()
    }

    /** Back in the launcher — drop any game title so presence reverts to the idle app name. */
    suspend fun clearCurrentGame() = setCurrentGame(null)

    /**
     * Broadcast or clear presence to match the current preference and connectivity. Safe to call
     * any time — when the session isn't connected the native bridge no-ops, and when sharing is off
     * it clears any presence. Call after connecting and whenever a preference changes.
     */
    suspend fun refresh() {
        if (!networkMonitor.isOnline()) return
        if (isShareEnabled()) {
            val name = when {
                isGenericMode() -> GENERIC_NAME          // hide the title even while playing
                else -> currentGame ?: APP_NAME          // real game title, else idle in the launcher
            }
            sessionActivator.setActivity(name, null)
        } else {
            sessionActivator.clearActivity()
        }
    }

    /**
     * Make an untrusted game title safe to broadcast: drop control and bidirectional-override
     * characters (anti-spoofing), collapse whitespace, and clamp to Discord's activity-name limit.
     */
    private fun sanitizeTitle(raw: String): String {
        val stripped = buildString(raw.length) {
            for (c in raw) {
                if (c.isISOControl() || c in BIDI_OVERRIDES) continue
                append(c)
            }
        }
        return stripped.replace(WHITESPACE, " ").trim().take(MAX_ACTIVITY_LEN)
    }

    private companion object {
        const val APP_NAME = "Playfield Portal"
        const val GENERIC_NAME = "a game"
        // Discord clamps activity name/details to 128 characters.
        const val MAX_ACTIVITY_LEN = 128
        val WHITESPACE = Regex("\\s+")
        // LRM/RLM/ALM + the LRE..PDI embedding/override/isolate format characters (category Cf,
        // so isISOControl() misses them). Written as \uXXXX escapes so they stay visible in source.
        val BIDI_OVERRIDES = setOf(
            '\u200E', '\u200F', '\u061C',
            '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
            '\u2066', '\u2067', '\u2068', '\u2069',
        )
    }
}
