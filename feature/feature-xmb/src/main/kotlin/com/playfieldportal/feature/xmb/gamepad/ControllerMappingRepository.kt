package com.playfieldportal.feature.xmb.gamepad

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_MAPPINGS = stringPreferencesKey("controller_mappings_v1")

@Singleton
class ControllerMappingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val mappings: Flow<GamepadMappings> = context.pfpDataStore.data
        .map { prefs ->
            val raw = prefs[KEY_MAPPINGS]
            if (raw != null) {
                runCatching { json.decodeFromString<GamepadMappings>(raw) }
                    .getOrElse {
                        Timber.w("Failed to parse controller mappings, using defaults")
                        GamepadMappings()
                    }
            } else {
                GamepadMappings()
            }
        }

    suspend fun saveMappings(mappings: GamepadMappings) {
        context.pfpDataStore.edit { prefs ->
            prefs[KEY_MAPPINGS] = json.encodeToString(mappings)
        }
        Timber.i("Controller mappings saved")
    }

    suspend fun resetToDefaults() {
        context.pfpDataStore.edit { prefs ->
            prefs.remove(KEY_MAPPINGS)
        }
        Timber.i("Controller mappings reset to defaults")
    }

    suspend fun remap(action: GamepadAction, newKeyCode: Int) {
        val current = context.pfpDataStore.data
            .map { prefs ->
                prefs[KEY_MAPPINGS]?.let {
                    runCatching { json.decodeFromString<GamepadMappings>(it) }.getOrNull()
                } ?: GamepadMappings()
            }
            .let { flow ->
                var result = GamepadMappings()
                // Collect just one value
                flow.collect { result = it; return@collect }
                result
            }

        // Remove any existing binding for this keycode or action, then add new one
        val updated = current.bindings
            .filter { it.keyCode != newKeyCode && it.action != action }
            .plus(GamepadBinding(newKeyCode, action))

        saveMappings(GamepadMappings(updated))
        Timber.i("Remapped $action → keycode $newKeyCode")
    }
}
