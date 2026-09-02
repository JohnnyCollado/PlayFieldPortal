package com.playfieldportal.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.ConfirmBackLayout
import com.playfieldportal.core.domain.model.ControllerDisplayType
import com.playfieldportal.core.domain.model.ControllerLayoutPrefs
import com.playfieldportal.core.domain.model.gamepadMappingsFor
import com.playfieldportal.core.domain.model.ScrollSpeed
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
private val KEY_SCROLL_SPEED = stringPreferencesKey("controller_scroll_speed")

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
            scrollSpeed = store[KEY_SCROLL_SPEED]
                ?.let { runCatching { ScrollSpeed.valueOf(it) }.getOrNull() }
                ?: ScrollSpeed.STANDARD,
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
    // The table itself is built by gamepadMappingsFor() in core-domain, so the
    // rebuild rule is pure and unit-tested rather than living behind DataStore.
    private suspend fun applyLayout(confirmBack: ConfirmBackLayout, xy: XYLayout) {
        mappingRepository.saveMappings(gamepadMappingsFor(confirmBack, xy))
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

    // ── Scroll speed ──────────────────────────────────────────────────────────

    suspend fun setScrollSpeed(speed: ScrollSpeed) {
        context.pfpDataStore.edit { it[KEY_SCROLL_SPEED] = speed.name }
        Timber.i("ScrollSpeed set: $speed")
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    suspend fun resetAllPrefs() {
        context.pfpDataStore.edit { store ->
            store.remove(KEY_CONFIRM_BACK)
            store.remove(KEY_XY_LAYOUT)
            store.remove(KEY_DISPLAY_TYPE)
            store.remove(KEY_SCROLL_SPEED)
        }
        mappingRepository.resetToDefaults()
        Timber.i("Controller layout prefs reset to defaults")
    }
}
