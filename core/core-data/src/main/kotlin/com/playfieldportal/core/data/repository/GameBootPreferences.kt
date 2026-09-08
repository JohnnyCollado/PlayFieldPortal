package com.playfieldportal.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * GameBoot presentation prefs — the PS3-style transition shown between confirming a game and the
 * emulator taking the screen.
 *
 * Lives in core-data (not feature-launcher) because TWO feature modules read the same key and
 * must never disagree: the [com.playfieldportal.feature.launcher.GameBootGate] gates the launch
 * on it, and the XMB confirm paths suppress the regular App Launch sound with it. One class, one
 * key, one default.
 *
 * Default OFF: enabling the presentation by default would add launch latency for every existing
 * user (design doc §11).
 */
@Singleton
class GameBootPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val gameBootEnabledFlow: Flow<Boolean> = context.pfpDataStore.data
        .map { it[KEY_GAMEBOOT_ENABLED] ?: false }

    suspend fun setGameBootEnabled(enabled: Boolean) =
        context.pfpDataStore.edit { it[KEY_GAMEBOOT_ENABLED] = enabled }

    companion object {
        private val KEY_GAMEBOOT_ENABLED = booleanPreferencesKey("display_gameboot_enabled")
    }
}
