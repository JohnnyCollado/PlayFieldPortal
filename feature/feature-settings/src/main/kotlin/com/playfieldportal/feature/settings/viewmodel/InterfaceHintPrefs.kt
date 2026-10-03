package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.TouchNavButtonMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Button hints and the on-screen touch button, as Display settings and the XMB read them. */
data class InterfaceHints(
    val enabled: Boolean = true,
    val delaySeconds: Float = DEFAULT_HINT_DELAY_SECONDS,
    val touchButton: TouchNavButtonMode = TouchNavButtonMode.AUTO,
)

internal const val DEFAULT_HINT_DELAY_SECONDS = 2.5f
internal const val MIN_HINT_DELAY_SECONDS = 1f
internal const val MAX_HINT_DELAY_SECONDS = 5f

/**
 * The hint and touch-button prefs, shared by Display settings and Initial Setup's Hints & Touch
 * page. The keys must match XMBViewModel's, which reads the same prefs.
 */
class InterfaceHintPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val hints: Flow<InterfaceHints> = context.pfpDataStore.data.map { prefs ->
        InterfaceHints(
            enabled = prefs[KEY_CONTEXT_MENU_HINT] ?: true,
            delaySeconds = (prefs[KEY_CONTEXT_MENU_HINT_DELAY_SECONDS] ?: DEFAULT_HINT_DELAY_SECONDS)
                .coerceIn(MIN_HINT_DELAY_SECONDS, MAX_HINT_DELAY_SECONDS),
            touchButton = TouchNavButtonMode.fromName(prefs[KEY_TOUCH_NAV_BUTTON]),
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.pfpDataStore.edit { it[KEY_CONTEXT_MENU_HINT] = enabled }
    }

    suspend fun setDelaySeconds(seconds: Float) {
        context.pfpDataStore.edit {
            it[KEY_CONTEXT_MENU_HINT_DELAY_SECONDS] = seconds.coerceIn(MIN_HINT_DELAY_SECONDS, MAX_HINT_DELAY_SECONDS)
        }
    }

    suspend fun setTouchButton(mode: TouchNavButtonMode) {
        context.pfpDataStore.edit { it[KEY_TOUCH_NAV_BUTTON] = mode.name }
    }

    companion object {
        // Must match XMBViewModel.KEY_TOUCH_NAV_BUTTON / KEY_CONTEXT_MENU_HINT(_DELAY_SECONDS).
        internal val KEY_TOUCH_NAV_BUTTON = stringPreferencesKey("interface_touch_nav_button")
        internal val KEY_CONTEXT_MENU_HINT = booleanPreferencesKey("interface_context_menu_hint")
        internal val KEY_CONTEXT_MENU_HINT_DELAY_SECONDS =
            floatPreferencesKey("interface_context_menu_hint_delay_seconds")
    }
}
