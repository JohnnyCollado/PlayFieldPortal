package com.playfieldportal.core.data.achievement

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.common.security.KeystoreSecretCipher
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_RA_USERNAME = stringPreferencesKey("ra_username")
private val KEY_RA_API_KEY = stringPreferencesKey("ra_api_key")
private val KEY_STEAM_ID64 = stringPreferencesKey("steam_id64")
private val KEY_STEAM_API_KEY = stringPreferencesKey("steam_api_key")
private val KEY_ENABLED = booleanPreferencesKey("achievements_enabled")
private val KEY_SYNC_LAST = longPreferencesKey("achievements_sync_last")

/**
 * Stores the user's achievement credentials. The two API keys are the only secrets and are
 * encrypted at rest with the hardware-backed [KeystoreSecretCipher] (AES-256/GCM), exactly like
 * the SteamGridDB key — they never touch DataStore in plaintext, and neither key is ever logged.
 * The RA username and SteamID64 are public identities, stored as-is.
 *
 * No password is ever handled: both providers are read-only public-data APIs. See
 * docs/shiba-coins-achievements-plan.md.
 */
@Singleton
class AchievementCredentialsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val raUsernameFlow: Flow<String?> =
        context.pfpDataStore.data.map { it[KEY_RA_USERNAME] }

    val steamId64Flow: Flow<String?> =
        context.pfpDataStore.data.map { it[KEY_STEAM_ID64] }

    val enabledFlow: Flow<Boolean> =
        context.pfpDataStore.data.map { it[KEY_ENABLED] ?: false }

    val lastSyncedAtFlow: Flow<Long?> =
        context.pfpDataStore.data.map { it[KEY_SYNC_LAST] }

    suspend fun raUsername(): String? = context.pfpDataStore.data.first()[KEY_RA_USERNAME]

    // decryptOrLegacy keeps any pre-encryption plaintext value working until it is next saved.
    suspend fun raApiKey(): String? =
        context.pfpDataStore.data.first()[KEY_RA_API_KEY]?.let { KeystoreSecretCipher.decryptOrLegacy(it) }

    suspend fun steamId64(): String? = context.pfpDataStore.data.first()[KEY_STEAM_ID64]

    suspend fun steamApiKey(): String? =
        context.pfpDataStore.data.first()[KEY_STEAM_API_KEY]?.let { KeystoreSecretCipher.decryptOrLegacy(it) }

    suspend fun lastSyncedAt(): Long? = context.pfpDataStore.data.first()[KEY_SYNC_LAST]

    suspend fun hasRetroAchievements(): Boolean =
        !raUsername().isNullOrBlank() && !raApiKey().isNullOrBlank()

    suspend fun hasSteam(): Boolean =
        !steamId64().isNullOrBlank() && !steamApiKey().isNullOrBlank()

    suspend fun saveRetroAchievements(username: String, apiKey: String) {
        context.pfpDataStore.edit {
            it[KEY_RA_USERNAME] = username.trim()
            it[KEY_RA_API_KEY] = KeystoreSecretCipher.encrypt(apiKey.trim())
        }
    }

    suspend fun saveSteam(steamId64: String, apiKey: String) {
        context.pfpDataStore.edit {
            it[KEY_STEAM_ID64] = steamId64.trim()
            it[KEY_STEAM_API_KEY] = KeystoreSecretCipher.encrypt(apiKey.trim())
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.pfpDataStore.edit { it[KEY_ENABLED] = enabled }
    }

    suspend fun setLastSyncedAt(epochMillis: Long) {
        context.pfpDataStore.edit { it[KEY_SYNC_LAST] = epochMillis }
    }

    /** Disconnects RetroAchievements only, leaving Steam and the enabled flag intact. */
    suspend fun clearRetroAchievements() {
        context.pfpDataStore.edit {
            it.remove(KEY_RA_USERNAME)
            it.remove(KEY_RA_API_KEY)
        }
    }

    /** Disconnects Steam only, leaving RetroAchievements and the enabled flag intact. */
    suspend fun clearSteam() {
        context.pfpDataStore.edit {
            it.remove(KEY_STEAM_ID64)
            it.remove(KEY_STEAM_API_KEY)
        }
    }

    /** Removes every stored credential and identity. Sync state and the enabled flag are cleared too. */
    suspend fun clear() {
        context.pfpDataStore.edit {
            it.remove(KEY_RA_USERNAME)
            it.remove(KEY_RA_API_KEY)
            it.remove(KEY_STEAM_ID64)
            it.remove(KEY_STEAM_API_KEY)
            it.remove(KEY_ENABLED)
            it.remove(KEY_SYNC_LAST)
        }
    }
}
