package com.playfieldportal.feature.artwork.api

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

private val KEY_SGDB_API_KEY = stringPreferencesKey("sgdb_api_key")

@Singleton
class SgdbApiKeyProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val apiKeyFlow: Flow<String?> = context.pfpDataStore.data
        .map { it[KEY_SGDB_API_KEY] }

    suspend fun getKey(): String? =
        context.pfpDataStore.data.first()[KEY_SGDB_API_KEY]

    suspend fun saveKey(key: String) {
        context.pfpDataStore.edit { it[KEY_SGDB_API_KEY] = key.trim() }
    }

    suspend fun clearKey() {
        context.pfpDataStore.edit { it.remove(KEY_SGDB_API_KEY) }
    }

    suspend fun hasKey(): Boolean = !getKey().isNullOrBlank()
}
