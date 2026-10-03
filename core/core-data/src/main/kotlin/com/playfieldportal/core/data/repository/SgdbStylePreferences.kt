package com.playfieldportal.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * The SteamGridDB styles the Artwork Studio's Style filter keeps, remembered per art type (user
 * decision, 2026-09-29): someone who only ever wants Material grids should not pick it every open.
 * Every other Studio filter lasts one open.
 *
 * Keyed by SteamGridDB's own endpoint name ("grids", "heroes", "logos", "icons") because core-data
 * does not know the artwork module's types. A missing or empty set means every style.
 */
@Singleton
class SgdbStylePreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Every remembered set, by art type. */
    suspend fun styles(): Map<String, Set<String>> =
        context.pfpDataStore.data.first().asMap()
            .mapNotNull { (key, value) ->
                val type = key.name.removePrefix(PREFIX).takeIf { key.name.startsWith(PREFIX) } ?: return@mapNotNull null
                @Suppress("UNCHECKED_CAST")
                (value as? Set<String>)?.takeIf { it.isNotEmpty() }?.let { type to it }
            }
            .toMap()

    suspend fun setStyles(type: String, styles: Set<String>) = context.pfpDataStore.edit {
        val key = stringSetPreferencesKey(PREFIX + type)
        if (styles.isEmpty()) it.remove(key) else it[key] = styles
    }

    /** Clear Filters: forgets every remembered style. */
    suspend fun clear() = context.pfpDataStore.edit { prefs ->
        prefs.asMap().keys.filter { it.name.startsWith(PREFIX) }.forEach { prefs.remove(it) }
    }

    private companion object {
        const val PREFIX = "studio_sgdb_styles_"
    }
}
