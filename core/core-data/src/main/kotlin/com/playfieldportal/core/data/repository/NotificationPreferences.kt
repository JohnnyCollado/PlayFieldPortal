package com.playfieldportal.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.repository.NotificationRetention
import com.playfieldportal.core.domain.repository.NotificationSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Settings ▸ Notifications, and the retention window the repository reads on every write.
 *
 * `mirrorToShade` stays on by default: the Android shade keeps working exactly as it does today,
 * and the panel adds what the shade cannot do on a HOME-screen device — history, actions, a clear.
 * Turning it off is for users who find the duplication noisy, not a replacement switch.
 */
@Singleton
class NotificationPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) : NotificationRetention, NotificationSettings {

    override val enabled: Flow<Boolean> = context.pfpDataStore.data
        .map { it[KEY_ENABLED] ?: DEFAULT_ENABLED }

    override val mirrorToShade: Flow<Boolean> = context.pfpDataStore.data
        .map { it[KEY_MIRROR_TO_SHADE] ?: DEFAULT_MIRROR_TO_SHADE }

    val autoClearDaysFlow: Flow<Int> = context.pfpDataStore.data
        .map { it[KEY_AUTO_CLEAR_DAYS] ?: NotificationRetention.DEFAULT_AUTO_CLEAR_DAYS }

    suspend fun setEnabled(enabled: Boolean) = context.pfpDataStore.edit {
        it[KEY_ENABLED] = enabled
    }

    suspend fun setMirrorToShade(mirror: Boolean) = context.pfpDataStore.edit {
        it[KEY_MIRROR_TO_SHADE] = mirror
    }

    /** 0 = never auto-clear; the row cap still applies. */
    suspend fun setAutoClearDays(days: Int) = context.pfpDataStore.edit {
        it[KEY_AUTO_CLEAR_DAYS] = days.coerceAtLeast(0)
    }

    override suspend fun autoClearDays(): Int = autoClearDaysFlow.first()

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("notifications_enabled")
        private val KEY_MIRROR_TO_SHADE = booleanPreferencesKey("notifications_mirror_to_shade")
        private val KEY_AUTO_CLEAR_DAYS = intPreferencesKey("notifications_auto_clear_days")

        const val DEFAULT_ENABLED = true
        const val DEFAULT_MIRROR_TO_SHADE = true
    }
}
