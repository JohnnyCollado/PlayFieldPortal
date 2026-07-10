package com.playfieldportal.feature.artwork.api

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtworkScrapePreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val preferSteamGridDbHeroesFlow: Flow<Boolean> =
        context.pfpDataStore.data.map { it[KEY_PREFER_SGDB_HEROES] ?: false }

    val downloadClearLogosFlow: Flow<Boolean> =
        context.pfpDataStore.data.map { it[KEY_DOWNLOAD_CLEAR_LOGOS] ?: true }

    val downloadHeroesFlow: Flow<Boolean> =
        context.pfpDataStore.data.map { it[KEY_DOWNLOAD_HEROES] ?: true }

    val downloadManualsFlow: Flow<Boolean> =
        context.pfpDataStore.data.map { it[KEY_DOWNLOAD_MANUALS] ?: false }

    val downloadVideoSnapsFlow: Flow<Boolean> =
        context.pfpDataStore.data.map { it[KEY_DOWNLOAD_VIDEO_SNAPS] ?: false }

    suspend fun getOptions(): ScrapeOptions {
        val prefs = context.pfpDataStore.data.first()
        return ScrapeOptions(
            preferSteamGridDbHeroes = prefs[KEY_PREFER_SGDB_HEROES]  ?: false,
            downloadClearLogos      = prefs[KEY_DOWNLOAD_CLEAR_LOGOS] ?: true,
            downloadHeroes          = prefs[KEY_DOWNLOAD_HEROES]      ?: true,
            downloadManuals         = prefs[KEY_DOWNLOAD_MANUALS]     ?: false,
            downloadVideoSnaps      = prefs[KEY_DOWNLOAD_VIDEO_SNAPS] ?: false,
        )
    }

    suspend fun setPreferSteamGridDbHeroes(value: Boolean) =
        context.pfpDataStore.edit { it[KEY_PREFER_SGDB_HEROES] = value }

    suspend fun setDownloadClearLogos(value: Boolean) =
        context.pfpDataStore.edit { it[KEY_DOWNLOAD_CLEAR_LOGOS] = value }

    suspend fun setDownloadHeroes(value: Boolean) =
        context.pfpDataStore.edit { it[KEY_DOWNLOAD_HEROES] = value }

    suspend fun setDownloadManuals(value: Boolean) =
        context.pfpDataStore.edit { it[KEY_DOWNLOAD_MANUALS] = value }

    suspend fun setDownloadVideoSnaps(value: Boolean) =
        context.pfpDataStore.edit { it[KEY_DOWNLOAD_VIDEO_SNAPS] = value }

    companion object {
        private val KEY_PREFER_SGDB_HEROES   = booleanPreferencesKey("pref_sgdb_heroes")
        private val KEY_DOWNLOAD_CLEAR_LOGOS = booleanPreferencesKey("pref_dl_clear_logos")
        private val KEY_DOWNLOAD_HEROES      = booleanPreferencesKey("pref_dl_heroes")
        private val KEY_DOWNLOAD_MANUALS     = booleanPreferencesKey("pref_dl_manuals")
        private val KEY_DOWNLOAD_VIDEO_SNAPS = booleanPreferencesKey("pref_dl_video_snaps")
    }
}
