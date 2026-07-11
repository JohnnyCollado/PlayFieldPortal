package com.playfieldportal.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.IconDisplayMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The global default [IconDisplayMode]; per-game overrides live on the game row. */
@Singleton
class IconDisplayPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val modeFlow: Flow<IconDisplayMode> = context.pfpDataStore.data
        .map { IconDisplayMode.fromName(it[KEY_MODE]) ?: IconDisplayMode.DEFAULT }

    suspend fun setMode(mode: IconDisplayMode) =
        context.pfpDataStore.edit { it[KEY_MODE] = mode.name }

    // "Animated icons" master switch for ICON1 video snaps in the icon slot (ICON0 mode only).
    val animatedIconsFlow: Flow<Boolean> = context.pfpDataStore.data
        .map { it[KEY_ANIMATED_ICONS] ?: true }

    suspend fun setAnimatedIcons(enabled: Boolean) =
        context.pfpDataStore.edit { it[KEY_ANIMATED_ICONS] = enabled }

    companion object {
        private val KEY_MODE = stringPreferencesKey("pref_icon_display_mode")
        private val KEY_ANIMATED_ICONS = androidx.datastore.preferences.core.booleanPreferencesKey("pref_animated_icons")
    }
}
