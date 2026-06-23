package com.playfieldportal.feature.artwork

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Stores API credentials for ScreenScraper and TheGamesDB in DataStore.
@Singleton
class MetadataApiKeyProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // ── ScreenScraper ─────────────────────────────────────────────────────────
    // Users register at screenscraper.fr — username/password give higher rate limits.
    // The app dev id/password must be obtained from screenscraper.fr/membreForum.php
    val ssUsernameFlow: Flow<String?> = context.pfpDataStore.data.map { it[KEY_SS_USERNAME] }
    val ssPasswordFlow: Flow<String?> = context.pfpDataStore.data.map { it[KEY_SS_PASSWORD] }

    suspend fun getSsUsername(): String? = ssUsernameFlow.firstValue()
    suspend fun getSsPassword(): String? = ssPasswordFlow.firstValue()

    suspend fun saveSsCredentials(username: String, password: String) {
        context.pfpDataStore.edit {
            it[KEY_SS_USERNAME] = username.trim()
            it[KEY_SS_PASSWORD] = password.trim()
        }
    }

    suspend fun clearSsCredentials() {
        context.pfpDataStore.edit {
            it.remove(KEY_SS_USERNAME)
            it.remove(KEY_SS_PASSWORD)
        }
    }

    // ── TheGamesDB ────────────────────────────────────────────────────────────
    // Free API key at thegamesdb.net
    val tgdbKeyFlow: Flow<String?> = context.pfpDataStore.data.map { it[KEY_TGDB_API_KEY] }

    suspend fun getTgdbKey(): String? = tgdbKeyFlow.firstValue()

    suspend fun saveTgdbKey(key: String) {
        context.pfpDataStore.edit { it[KEY_TGDB_API_KEY] = key.trim() }
    }

    suspend fun clearTgdbKey() {
        context.pfpDataStore.edit { it.remove(KEY_TGDB_API_KEY) }
    }

    // ── IGDB ──────────────────────────────────────────────────────────────────
    // Credentials from dev.twitch.tv — Client ID + Client Secret for client_credentials flow.
    val igdbClientIdFlow: Flow<String?> = context.pfpDataStore.data.map { it[KEY_IGDB_CLIENT_ID] }

    suspend fun getIgdbClientId(): String?     = igdbClientIdFlow.firstValue()
    suspend fun getIgdbClientSecret(): String? =
        context.pfpDataStore.data.first()[KEY_IGDB_CLIENT_SECRET]

    suspend fun saveIgdbCredentials(clientId: String, clientSecret: String) {
        context.pfpDataStore.edit {
            it[KEY_IGDB_CLIENT_ID]     = clientId.trim()
            it[KEY_IGDB_CLIENT_SECRET] = clientSecret.trim()
        }
    }

    suspend fun clearIgdbCredentials() {
        context.pfpDataStore.edit {
            it.remove(KEY_IGDB_CLIENT_ID)
            it.remove(KEY_IGDB_CLIENT_SECRET)
        }
    }

    suspend fun hasSsCredentials(): Boolean = getSsUsername()?.isNotBlank() == true
    suspend fun hasTgdbKey(): Boolean = getTgdbKey()?.isNotBlank() == true
    suspend fun hasIgdbCredentials(): Boolean = getIgdbClientId()?.isNotBlank() == true &&
        getIgdbClientSecret()?.isNotBlank() == true

    private suspend fun Flow<String?>.firstValue(): String? = first()

    companion object {
        private val KEY_SS_USERNAME      = stringPreferencesKey("ss_username")
        private val KEY_SS_PASSWORD      = stringPreferencesKey("ss_password")
        private val KEY_TGDB_API_KEY     = stringPreferencesKey("tgdb_api_key")
        private val KEY_IGDB_CLIENT_ID     = stringPreferencesKey("igdb_client_id")
        private val KEY_IGDB_CLIENT_SECRET = stringPreferencesKey("igdb_client_secret")

        // Register your app at screenscraper.fr to get dev credentials.
        const val SS_DEV_ID       = "PlayFieldPortal"
        const val SS_DEV_PASSWORD = ""   // fill in after registration
        const val SS_SOFT_NAME    = "PlayFieldPortal"
    }
}
