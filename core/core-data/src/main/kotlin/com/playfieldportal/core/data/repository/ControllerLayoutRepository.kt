package com.playfieldportal.core.data.repository

import android.content.Context
import android.view.KeyEvent
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.ConfirmBackLayout
import com.playfieldportal.core.domain.model.ControllerDisplayType
import com.playfieldportal.core.domain.model.ControllerLayoutPrefs
import com.playfieldportal.core.domain.model.DEFAULT_BINDINGS
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.GamepadBinding
import com.playfieldportal.core.domain.model.GamepadMappings
import com.playfieldportal.core.domain.model.XYLayout
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        applyLayout(confirmBack = layout, xy = currentXyLayout())
        Timber.i("ConfirmBackLayout set: $layout")
    }

    // ── X / Y swap ────────────────────────────────────────────────────────────

    suspend fun setXYLayout(layout: XYLayout) {
        context.pfpDataStore.edit { it[KEY_XY_LAYOUT] = layout.name }
        applyLayout(confirmBack = currentConfirmBackLayout(), xy = layout)
        Timber.i("XYLayout set: $layout")
    }

    // ── Binding rebuild ─────────────────────────────────────────────────────────
    //
    // Rebuild the full mapping set from DEFAULT_BINDINGS on every change rather than
    // mutating individual bindings. This is idempotent (toggling back and forth always
    // lands on a clean default state) and preserves every non-face-button binding —
    // including the SELECT/BACK aliases (Enter, D-pad center, hardware Back) that the old
    // per-action remap path stripped away.
    private suspend fun applyLayout(confirmBack: ConfirmBackLayout, xy: XYLayout) {
        val confirmKey = if (confirmBack == ConfirmBackLayout.STANDARD) KeyEvent.KEYCODE_BUTTON_A else KeyEvent.KEYCODE_BUTTON_B
        val taskTrayKey = if (xy == XYLayout.STANDARD) KeyEvent.KEYCODE_BUTTON_X else KeyEvent.KEYCODE_BUTTON_Y

        val bindings = DEFAULT_BINDINGS.map { binding ->
            when (binding.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B ->
                    GamepadBinding(binding.keyCode, if (binding.keyCode == confirmKey) GamepadAction.SELECT else GamepadAction.BACK)
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y ->
                    GamepadBinding(binding.keyCode, if (binding.keyCode == taskTrayKey) GamepadAction.OPEN_TASK_TRAY else GamepadAction.LONG_PRESS)
                else -> binding
            }
        }
        mappingRepository.saveMappings(GamepadMappings(bindings))
    }

    private suspend fun currentConfirmBackLayout(): ConfirmBackLayout =
        context.pfpDataStore.data.first()[KEY_CONFIRM_BACK]
            ?.let { runCatching { ConfirmBackLayout.valueOf(it) }.getOrNull() }
            ?: ConfirmBackLayout.STANDARD

    private suspend fun currentXyLayout(): XYLayout =
        context.pfpDataStore.data.first()[KEY_XY_LAYOUT]
            ?.let { runCatching { XYLayout.valueOf(it) }.getOrNull() }
            ?: XYLayout.STANDARD

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
