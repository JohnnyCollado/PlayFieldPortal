package com.playfieldportal.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The GameBoot switch — one boolean for the one presentation shown between confirming a game and
 * the emulator taking the screen. On means the GameBoot sequence plays, sound and all; off means
 * nothing plays at all — the animation and its sound both stay down, and the launch is silent by
 * decision (a game boot is never scored by the menu's App Launch sound, which is the same
 * sfx_launch sample the built-in sequence is timed to).
 *
 * Lives in core-data (not feature-launcher) because THREE feature modules read the same key and
 * must never disagree: the [com.playfieldportal.feature.launcher.GameBootGate] gates the launch
 * on it, the XMB and Game Detail confirm paths keep their launch-sound suppression keyed on the
 * fact that a game boot is a GameBoot boot (on or off), and the Display settings screen renders
 * and toggles it.
 *
 * **Migration is read-time** — there is no one-shot pass to miss, and [resolve] IS the migration.
 * The short-lived three-way `display_gameboot_mode` key (never released) reads back as on/off, and
 * an install that never touched GameBoot keeps the old silent default (off for an established
 * install, on for a genuinely fresh one — "new installs get the sequence, existing users keep what
 * they have").
 *
 * The one write-side exception to "read-time only": [setGameBootEnabled] REMOVES the legacy mode
 * key in the same atomic edit it writes the boolean. That is what makes the precedence in [resolve]
 * unambiguous — while the mode key exists it is the user's most recent choice and wins; the moment
 * they touch the toggle it is gone and the boolean is authoritative forever.
 */
@Singleton
class GameBootPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val gameBootEnabledFlow: Flow<Boolean> = context.pfpDataStore.data
        .map { resolve(it) }

    suspend fun setGameBootEnabled(enabled: Boolean) = context.pfpDataStore.edit {
        it[KEY_GAMEBOOT_ENABLED] = enabled
        // See the class KDoc: retiring the legacy key here is what keeps resolve() unambiguous.
        it.remove(KEY_GAMEBOOT_MODE)
    }

    companion object {
        private val KEY_GAMEBOOT_ENABLED = booleanPreferencesKey("display_gameboot_enabled")
        // The unreleased three-way mode key — read by the migration, never written again.
        private val KEY_GAMEBOOT_MODE = stringPreferencesKey("display_gameboot_mode")
        // First-run marker owned by InitialSetupViewModel / XMBViewModel (same key string).
        private val KEY_INITIAL_SETUP_SEEN = booleanPreferencesKey("initial_setup_seen")

        /** The one persisted value that meant "GameBoot off" in the retired three-way mode. */
        private const val LEGACY_MODE_OFF = "OFF"

        /**
         * The full read-time migration, shared by [gameBootEnabledFlow] and the Display settings
         * screen so the row can never disagree with the gate:
         *
         *  1. `display_gameboot_mode` present → anything but `OFF` means on. Only reachable until
         *     the user next touches the toggle, which removes the key.
         *  2. else `display_gameboot_enabled` present → the boolean verbatim.
         *  3. else `initial_setup_seen` true → an established install that never touched
         *     GameBoot → off (the old default).
         *  4. else → a fresh install → on.
         */
        fun resolve(prefs: Preferences): Boolean = when {
            prefs[KEY_GAMEBOOT_MODE] != null -> prefs[KEY_GAMEBOOT_MODE] != LEGACY_MODE_OFF
            prefs[KEY_GAMEBOOT_ENABLED] != null -> prefs[KEY_GAMEBOOT_ENABLED] == true
            prefs[KEY_INITIAL_SETUP_SEEN] == true -> false
            else -> true
        }
    }
}
