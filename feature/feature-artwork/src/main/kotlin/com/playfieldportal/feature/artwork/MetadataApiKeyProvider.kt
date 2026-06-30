package com.playfieldportal.feature.artwork

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.common.security.KeystoreSecretCipher
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataApiKeyProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Secrets (TGDB key, IGDB client secret) are encrypted at rest via the Keystore-backed cipher;
    // the IGDB client id is a public identifier and stays plaintext. decryptOrLegacy keeps any
    // pre-encryption values working until they're re-saved.
    // ── TheGamesDB ────────────────────────────────────────────────────────────
    val tgdbKeyFlow: Flow<String?> = context.pfpDataStore.data
        .map { prefs -> prefs[KEY_TGDB_API_KEY]?.let { KeystoreSecretCipher.decryptOrLegacy(it) } }

    suspend fun getTgdbKey(): String? = tgdbKeyFlow.first()

    suspend fun saveTgdbKey(key: String) {
        context.pfpDataStore.edit { it[KEY_TGDB_API_KEY] = KeystoreSecretCipher.encrypt(key.trim()) }
    }

    suspend fun clearTgdbKey() {
        context.pfpDataStore.edit { it.remove(KEY_TGDB_API_KEY) }
    }

    // ── IGDB ──────────────────────────────────────────────────────────────────
    val igdbClientIdFlow: Flow<String?> = context.pfpDataStore.data.map { it[KEY_IGDB_CLIENT_ID] }

    suspend fun getIgdbClientId(): String? = igdbClientIdFlow.first()
    suspend fun getIgdbClientSecret(): String? =
        context.pfpDataStore.data.first()[KEY_IGDB_CLIENT_SECRET]?.let { KeystoreSecretCipher.decryptOrLegacy(it) }

    suspend fun saveIgdbCredentials(clientId: String, clientSecret: String) {
        context.pfpDataStore.edit {
            it[KEY_IGDB_CLIENT_ID]     = clientId.trim()
            it[KEY_IGDB_CLIENT_SECRET] = KeystoreSecretCipher.encrypt(clientSecret.trim())
        }
    }

    suspend fun clearIgdbCredentials() {
        context.pfpDataStore.edit {
            it.remove(KEY_IGDB_CLIENT_ID)
            it.remove(KEY_IGDB_CLIENT_SECRET)
        }
    }

    suspend fun hasTgdbKey(): Boolean = getTgdbKey()?.isNotBlank() == true
    suspend fun hasIgdbCredentials(): Boolean = getIgdbClientId()?.isNotBlank() == true &&
        getIgdbClientSecret()?.isNotBlank() == true

    companion object {
        private val KEY_TGDB_API_KEY       = stringPreferencesKey("tgdb_api_key")
        private val KEY_IGDB_CLIENT_ID     = stringPreferencesKey("igdb_client_id")
        private val KEY_IGDB_CLIENT_SECRET = stringPreferencesKey("igdb_client_secret")
    }
}
