package com.playfieldportal.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.ListSortMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The global sort for game lists and for app lists. A list with its own sort (see
 * [ListStateRepository.sortOverrides]) beats these; resolution order is list > global.
 */
@Singleton
class SortPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val gamesSortFlow: Flow<ListSortMode> = context.pfpDataStore.data.map { decode(it[KEY_GAMES]) }

    val appsSortFlow: Flow<ListSortMode> = context.pfpDataStore.data.map { decode(it[KEY_APPS]) }

    suspend fun setGamesSort(mode: ListSortMode) =
        context.pfpDataStore.edit { it[KEY_GAMES] = ListSortMode.asGlobal(mode).name }

    suspend fun setAppsSort(mode: ListSortMode) =
        context.pfpDataStore.edit { it[KEY_APPS] = ListSortMode.asGlobal(mode).name }

    companion object {
        private val KEY_GAMES = stringPreferencesKey("pref_sort_mode_games")
        private val KEY_APPS = stringPreferencesKey("pref_sort_mode_apps")

        // Unset or unreadable falls back to Title; CUSTOM is per-list and never global.
        fun decode(stored: String?): ListSortMode =
            ListSortMode.asGlobal(ListSortMode.fromName(stored) ?: ListSortMode.TITLE)
    }
}
