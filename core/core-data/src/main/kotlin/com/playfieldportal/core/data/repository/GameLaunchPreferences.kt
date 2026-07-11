package com.playfieldportal.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What confirming (tap / ✕) a game entity on the XMB does:
 *  • false (default) — open the Game Detail screen first, launch from its Play button;
 *  • true — launch straight into the game (Game Detail auto-fires its launch on open, so
 *    every launch path — emulator intent, native app, shortcut, stored intent — stays in
 *    one place; "View Game Details" in the △ menu still opens the screen for edits).
 */
@Singleton
class GameLaunchPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val directLaunchFlow: Flow<Boolean> = context.pfpDataStore.data
        .map { it[KEY_DIRECT_LAUNCH] ?: false }

    suspend fun setDirectLaunch(enabled: Boolean) =
        context.pfpDataStore.edit { it[KEY_DIRECT_LAUNCH] = enabled }

    companion object {
        private val KEY_DIRECT_LAUNCH = booleanPreferencesKey("pref_direct_game_launch")
    }
}
