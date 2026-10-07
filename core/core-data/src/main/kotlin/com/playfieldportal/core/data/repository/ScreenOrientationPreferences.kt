package com.playfieldportal.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.ScreenOrientationMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Display ▸ Screen Orientation. MainActivity applies [modeFlow] to the window; the Display
 * settings screen writes it (by [KEY_MODE], alongside its other keys).
 */
@Singleton
class ScreenOrientationPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val modeFlow: Flow<ScreenOrientationMode> = context.pfpDataStore.data
        .map { ScreenOrientationMode.fromName(it[KEY_MODE]) }
        // A failed read must still resolve: MainActivity holds the splash until this emits.
        .catch { emit(ScreenOrientationMode.LANDSCAPE) }
        .distinctUntilChanged()

    companion object {
        /** Carried by BackupManager — see BackupKeyCoverageTest. */
        val KEY_MODE = stringPreferencesKey("display_screen_orientation")
    }
}
