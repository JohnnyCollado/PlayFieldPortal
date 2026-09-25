package com.playfieldportal.feature.artwork.api

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

    // Manuals default ON (small PDFs relative to the cap; surfaced on Game Detail later).
    val downloadManualsFlow: Flow<Boolean> =
        context.pfpDataStore.data.map { it[KEY_DOWNLOAD_MANUALS] ?: true }

    val downloadVideoSnapsFlow: Flow<Boolean> =
        context.pfpDataStore.data.map { it[KEY_DOWNLOAD_VIDEO_SNAPS] ?: false }

    /**
     * Preferred artwork region (C22 task T6), as a ScreenScraper region code — `us`, `eu`, `jp`,
     * `wor`. Null means "no preference", which leaves the walk at its `wor → us → eu → jp`
     * default: exactly the order that shipped before this preference existed, so an untouched
     * install picks the same art it always did.
     */
    val artworkRegionFlow: Flow<String?> =
        context.pfpDataStore.data.map { it[KEY_ARTWORK_REGION] }

    suspend fun getArtworkRegion(): String? = context.pfpDataStore.data.first()[KEY_ARTWORK_REGION]

    suspend fun setArtworkRegion(region: String?) =
        context.pfpDataStore.edit { prefs ->
            if (region.isNullOrBlank()) prefs.remove(KEY_ARTWORK_REGION)
            else prefs[KEY_ARTWORK_REGION] = region
        }

    suspend fun getOptions(): ScrapeOptions {
        val prefs = context.pfpDataStore.data.first()
        return ScrapeOptions(
            preferSteamGridDbHeroes = prefs[KEY_PREFER_SGDB_HEROES]  ?: false,
            downloadClearLogos      = prefs[KEY_DOWNLOAD_CLEAR_LOGOS] ?: true,
            downloadHeroes          = prefs[KEY_DOWNLOAD_HEROES]      ?: true,
            downloadManuals         = prefs[KEY_DOWNLOAD_MANUALS]     ?: true,
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
        private val KEY_ARTWORK_REGION       = stringPreferencesKey("pref_artwork_region")

        /** The regions offered in Settings, in menu order. `null` label is "No preference". */
        val ARTWORK_REGIONS: List<Pair<String?, String>> = listOf(
            null to "No preference",
            "us" to "USA",
            "eu" to "Europe",
            "jp" to "Japan",
            "wor" to "World",
        )
    }
}
