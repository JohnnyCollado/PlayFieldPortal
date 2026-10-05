package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.repository.LockScreenImage
import com.playfieldportal.core.data.repository.SafeMedia.readCapped
import com.playfieldportal.core.data.repository.ThemePrefKeys
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class LockScreenUiState(
    /** True while PFP has set the device lock screen. */
    val isSet: Boolean = false,
    /** Set by a theme rather than chosen here. */
    val fromTheme: Boolean = false,
    /** The launcher has a still (or a motion wallpaper's poster) to reuse. */
    val hasLauncherWallpaper: Boolean = false,
    val busy: Boolean = false,
)

/**
 * Display ▸ Lock Screen Image: choose an image, reuse the launcher's own wallpaper (the poster, for
 * a motion wallpaper), or reset the device lock screen. Outcomes go to the notification tray.
 */
@HiltViewModel
class LockScreenViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lockScreen: LockScreenImage,
    private val tasks: BackgroundTaskCenter,
) : ViewModel() {

    private val busy = MutableStateFlow(false)

    val uiState: StateFlow<LockScreenUiState> = combine(
        lockScreen.state,
        context.pfpDataStore.data,
        busy,
    ) { lock, prefs, working ->
        LockScreenUiState(
            isSet = lock.path != null,
            fromTheme = lock.source == LockScreenImage.Source.THEME,
            hasLauncherWallpaper = prefs[ThemePrefKeys.CUSTOM_WALLPAPER] != null,
            busy = working,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LockScreenUiState())

    fun onImagePicked(uri: Uri) = run {
        val bytes = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readCapped(MAX_PICK_BYTES) } }.getOrNull()
        } ?: return@run LockScreenImage.Result.Failed("That image could not be read")
        lockScreen.set(bytes, LockScreenImage.Source.USER)
    }

    fun useLauncherWallpaper() = run {
        val path = context.pfpDataStore.data.first()[ThemePrefKeys.CUSTOM_WALLPAPER]
            ?: return@run LockScreenImage.Result.Failed("The launcher has no wallpaper image to use")
        lockScreen.setFromFile(path, LockScreenImage.Source.USER)
    }

    fun reset() {
        viewModelScope.launch {
            busy.value = true
            lockScreen.clear()
            busy.value = false
            report("Lock screen reset to the device default", NotificationSeverity.SUCCESS)
        }
    }

    private fun run(block: suspend () -> LockScreenImage.Result) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            val result = block()
            busy.value = false
            when (result) {
                LockScreenImage.Result.Set -> report("Lock screen image set", NotificationSeverity.SUCCESS)
                is LockScreenImage.Result.Failed -> report(result.reason, NotificationSeverity.ERROR)
            }
        }
    }

    private fun report(message: String, severity: NotificationSeverity) {
        tasks.report(
            id = TRAY_ID,
            label = "Lock Screen",
            message = message,
            severity = severity,
            action = NotificationAction.OpenSettingsScreen("settings_display"),
        )
    }

    companion object {
        const val TRAY_ID = "lockscreen_result"
        private const val MAX_PICK_BYTES = 32L * 1024 * 1024
    }
}
