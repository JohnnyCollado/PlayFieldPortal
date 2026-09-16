package com.playfieldportal.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Whether the Artwork Studio's crop editor shows the live result inset (C16 tasks 6.2 / 6.6).
 *
 * Lives in core-data rather than in the Studio because two screens write it and must not disagree:
 * Settings ▸ Artwork has the durable row, and the crop editor itself has a Ⓨ toggle for the moment
 * you notice the inset sitting over the part you are trying to frame.
 *
 * **One switch for every kind** (user decision, 2026-09-16), stills and video alike. Off means no
 * inset anywhere — which also means no second decoder for ICON1 and VIDEO, so this doubles as the
 * escape hatch if the playing preview ever costs too much on a given device.
 *
 * Defaults to on: the preview is the point of 6.2, and a user who has not expressed a preference
 * should see the feature.
 */
@Singleton
class CropPreviewPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val enabledFlow: Flow<Boolean> = context.pfpDataStore.data
        .map { it[KEY_CROP_PREVIEW_ENABLED] ?: DEFAULT_ENABLED }

    suspend fun setEnabled(enabled: Boolean) = context.pfpDataStore.edit {
        it[KEY_CROP_PREVIEW_ENABLED] = enabled
    }

    companion object {
        private val KEY_CROP_PREVIEW_ENABLED = booleanPreferencesKey("artwork_crop_preview_enabled")

        /** On until the user says otherwise. */
        const val DEFAULT_ENABLED = true
    }
}
