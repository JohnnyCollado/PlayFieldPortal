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
 * you're in Playfield Portal, never anything outside it. **Generic mode** replaces the app name
 * with a neutral "a game" so even that is hidden. Nothing here is broadcast until the user opts in.
 */
@Singleton
class DiscordPresenceController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionActivator: DiscordSessionActivator,
    private val networkMonitor: NetworkMonitor,
) {
    private val shareKey = booleanPreferencesKey("discord_share_activity")
    private val genericKey = booleanPreferencesKey("discord_generic_activity")

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
     * Broadcast or clear presence to match the current preference and connectivity. Safe to call
     * any time — when the session isn't connected the native bridge no-ops, and when sharing is off
     * it clears any presence. Call after connecting and whenever a preference changes.
     */
    suspend fun refresh() {
        if (!networkMonitor.isOnline()) return
        if (isShareEnabled()) {
            val name = if (isGenericMode()) GENERIC_NAME else APP_NAME
            sessionActivator.setActivity(name, null)
        } else {
            sessionActivator.clearActivity()
        }
    }

    private companion object {
        const val APP_NAME = "Playfield Portal"
        const val GENERIC_NAME = "a game"
    }
}
