package com.playfieldportal.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.IconDisplayMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The global default [IconDisplayMode], plus per-console overrides. Per-game overrides live on
 * the game row and beat both; resolution order is game > console > global.
 */
@Singleton
class IconDisplayPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val modeFlow: Flow<IconDisplayMode> = context.pfpDataStore.data
        .map { IconDisplayMode.fromName(it[KEY_MODE]) ?: IconDisplayMode.DEFAULT }

    suspend fun setMode(mode: IconDisplayMode) =
        context.pfpDataStore.edit { it[KEY_MODE] = mode.name }

    // Per-console overrides, keyed by platform id. A console absent from the map follows the
    // global mode. Kept as ONE encoded string rather than a key per console so the backup key
    // list can carry them by name (see BackupKeyCoverageTest).
    val platformModesFlow: Flow<Map<String, IconDisplayMode>> = context.pfpDataStore.data
        .map { decodePlatformModes(it[KEY_PLATFORM_MODES]) }

    /** [mode] = null clears the console's override so it follows the global setting again. */
    suspend fun setPlatformMode(platformId: String, mode: IconDisplayMode?) =
        context.pfpDataStore.edit { prefs ->
            val updated = withPlatformMode(decodePlatformModes(prefs[KEY_PLATFORM_MODES]), platformId, mode)
            prefs[KEY_PLATFORM_MODES] = encodePlatformModes(updated)
        }

    // "Animated icons" master switch for ICON1 video snaps in the icon slot (ICON0 mode only).
    val animatedIconsFlow: Flow<Boolean> = context.pfpDataStore.data
        .map { it[KEY_ANIMATED_ICONS] ?: true }

    suspend fun setAnimatedIcons(enabled: Boolean) =
        context.pfpDataStore.edit { it[KEY_ANIMATED_ICONS] = enabled }

    // How long the cursor must rest on a game (ICON0 tile) before its ICON1 video snap plays.
    // Seconds, clamped to 1..5; the 1.5 s default keeps the PSP's rest-then-animate cadence.
    val lingerDelaySecondsFlow: Flow<Float> = context.pfpDataStore.data
        .map { (it[KEY_ICON1_LINGER_DELAY_SECONDS] ?: 1.5f).coerceIn(1f, 5f) }

    suspend fun setLingerDelaySeconds(seconds: Float) =
        context.pfpDataStore.edit { it[KEY_ICON1_LINGER_DELAY_SECONDS] = seconds.coerceIn(1f, 5f) }

    companion object {
        private val KEY_MODE = stringPreferencesKey("pref_icon_display_mode")
        private val KEY_PLATFORM_MODES = stringPreferencesKey("pref_icon_display_mode_by_platform")
        private val KEY_ANIMATED_ICONS = androidx.datastore.preferences.core.booleanPreferencesKey("pref_animated_icons")
        private val KEY_ICON1_LINGER_DELAY_SECONDS =
            floatPreferencesKey("pref_icon1_linger_delay_seconds")

        // "platformId=MODE" per line. Pure and internal-free so the encoding is unit-testable;
        // anything unparseable is dropped rather than failing the whole read, so one bad entry
        // never costs the user every other console's choice.
        fun encodePlatformModes(modes: Map<String, IconDisplayMode>): String =
            modes.entries.joinToString("\n") { "${it.key}=${it.value.name}" }

        fun decodePlatformModes(encoded: String?): Map<String, IconDisplayMode> =
            encoded.orEmpty().lineSequence().mapNotNull { line ->
                val platformId = line.substringBefore('=', "").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val mode = IconDisplayMode.fromName(line.substringAfter('=', ""))
                    ?: return@mapNotNull null
                platformId to mode
            }.toMap()

        fun withPlatformMode(
            modes: Map<String, IconDisplayMode>,
            platformId: String,
            mode: IconDisplayMode?,
        ): Map<String, IconDisplayMode> =
            if (mode == null) modes - platformId else modes + (platformId to mode)
    }
}
