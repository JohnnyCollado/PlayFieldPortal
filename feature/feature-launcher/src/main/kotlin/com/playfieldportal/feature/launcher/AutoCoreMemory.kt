package com.playfieldportal.feature.launcher

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which RetroArch core each console's games last launched with, so the automatic
 * emulator pick for a console stays stable across detection passes.
 *
 * Why this exists: RetroArch scopes saved configs per core (and per content directory), so a
 * config "saved for a console" only applies to games launched with that exact core. PFP offers
 * several interchangeable libretro cores per console, and the automatic pick used to be simply
 * the first core in detection order — a set that changes as cores are installed and removed,
 * silently swapping the core a console's games launch with and detaching every per-core config
 * the user had saved. Remembering the last-launched core and preferring it while it is still
 * installed keeps the console's core — and therefore its RetroArch configs — stable.
 *
 * Only the AUTOMATIC rung of the launch ladder consults this memory: explicit choices
 * (per-game override, memory-card emulator, platform default) already pin a profile and are
 * unaffected. The memory is refreshed only when a RetroArch core launch actually reached the
 * emulator ([LaunchDispatcher]), so a launch that never happened can never pin a broken core.
 */
@Singleton
class AutoCoreMemory @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The recorded core profile id for [platformId], or null when none has ever been recorded. */
    suspend fun rememberedProfileId(platformId: String): String? = rememberedIds()[platformId]

    /** Every recorded platform → core profile id pair, for batch consumers. */
    suspend fun rememberedIds(): Map<String, String> = decode(context.pfpDataStore.data.first()[KEY])

    suspend fun remember(platformId: String, profileId: String) {
        runCatching {
            context.pfpDataStore.edit { prefs ->
                prefs[KEY] = json.encodeToString(decode(prefs[KEY]) + (platformId to profileId))
            }
        }.onFailure { Timber.w(it, "Could not remember auto core for platform $platformId") }
    }

    private fun decode(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, String>>(raw)
        }.getOrElse {
            Timber.w(it, "Corrupt auto-core memory ignored (treating as empty)")
            emptyMap()
        }
    }

    companion object {
        private val KEY = stringPreferencesKey("auto_resolved_cores")
    }
}