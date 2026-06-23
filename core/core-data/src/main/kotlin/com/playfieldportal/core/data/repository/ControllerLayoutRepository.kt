package com.playfieldportal.core.data.repository

import android.content.Context
import android.view.KeyEvent
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.ConfirmBackLayout
import com.playfieldportal.core.domain.model.ControllerDisplayType
import com.playfieldportal.core.domain.model.ControllerLayoutPrefs
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.XYLayout
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_CONFIRM_BACK = stringPreferencesKey("controller_confirm_back_layout")
private val KEY_XY_LAYOUT    = stringPreferencesKey("controller_xy_layout")
private val KEY_DISPLAY_TYPE = stringPreferencesKey("controller_display_type")

@Singleton
class ControllerLayoutRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mappingRepository: ControllerMappingRepository,
) {

    val prefs: Flow<ControllerLayoutPrefs> = context.pfpDataStore.data.map { store ->
        ControllerLayoutPrefs(
            confirmBackLayout = store[KEY_CONFIRM_BACK]
                ?.let { runCatching { ConfirmBackLayout.valueOf(it) }.getOrNull() }
                ?: ConfirmBackLayout.STANDARD,
            xyLayout = store[KEY_XY_LAYOUT]
                ?.let { runCatching { XYLayout.valueOf(it) }.getOrNull() }
                ?: XYLayout.STANDARD,
            displayType = store[KEY_DISPLAY_TYPE]
                ?.let { runCatching { ControllerDisplayType.valueOf(it) }.getOrNull() }
                ?: ControllerDisplayType.XBOX,
        )
    }

    // ── Confirm / Back swap ───────────────────────────────────────────────────

    suspend fun setConfirmBackLayout(layout: ConfirmBackLayout) {
        context.pfpDataStore.edit { it[KEY_CONFIRM_BACK] = layout.name }
        when (layout) {
            ConfirmBackLayout.STANDARD -> {
                mappingRepository.remap(GamepadAction.SELECT, KeyEvent.KEYCODE_BUTTON_A)
                mappingRepository.remap(GamepadAction.BACK,   KeyEvent.KEYCODE_BUTTON_B)
            }
            ConfirmBackLayout.REVERSED -> {
                mappingRepository.remap(GamepadAction.SELECT, KeyEvent.KEYCODE_BUTTON_B)
                mappingRepository.remap(GamepadAction.BACK,   KeyEvent.KEYCODE_BUTTON_A)
            }
        }
        Timber.i("ConfirmBackLayout set: $layout")
    }

    // ── X / Y swap ────────────────────────────────────────────────────────────

    suspend fun setXYLayout(layout: XYLayout) {
        context.pfpDataStore.edit { it[KEY_XY_LAYOUT] = layout.name }
        when (layout) {
            XYLayout.STANDARD -> {
                mappingRepository.remap(GamepadAction.OPEN_TASK_TRAY, KeyEvent.KEYCODE_BUTTON_X)
                mappingRepository.remap(GamepadAction.LONG_PRESS,     KeyEvent.KEYCODE_BUTTON_Y)
            }
            XYLayout.SWAPPED -> {
                mappingRepository.remap(GamepadAction.LONG_PRESS,     KeyEvent.KEYCODE_BUTTON_X)
                mappingRepository.remap(GamepadAction.OPEN_TASK_TRAY, KeyEvent.KEYCODE_BUTTON_Y)
            }
        }
        Timber.i("XYLayout set: $layout")
    }

    // ── Display type ──────────────────────────────────────────────────────────

    suspend fun setDisplayType(type: ControllerDisplayType) {
        context.pfpDataStore.edit { it[KEY_DISPLAY_TYPE] = type.name }
        Timber.i("ControllerDisplayType set: $type")
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    suspend fun resetAllPrefs() {
        context.pfpDataStore.edit { store ->
            store.remove(KEY_CONFIRM_BACK)
            store.remove(KEY_XY_LAYOUT)
            store.remove(KEY_DISPLAY_TYPE)
        }
        mappingRepository.resetToDefaults()
        Timber.i("Controller layout prefs reset to defaults")
    }
}
