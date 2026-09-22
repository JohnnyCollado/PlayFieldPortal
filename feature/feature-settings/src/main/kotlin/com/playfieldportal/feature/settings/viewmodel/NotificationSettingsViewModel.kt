package com.playfieldportal.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.repository.NotificationPreferences
import com.playfieldportal.core.domain.repository.NotificationRepository
import com.playfieldportal.core.domain.repository.NotificationRetention
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NotificationSettingsUiState(
    val enabled: Boolean = NotificationPreferences.DEFAULT_ENABLED,
    val mirrorToShade: Boolean = NotificationPreferences.DEFAULT_MIRROR_TO_SHADE,
    val autoClearDays: Int = NotificationRetention.DEFAULT_AUTO_CLEAR_DAYS,
    val storedCount: Int = 0,
    val unreadCount: Int = 0,
)

@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    private val preferences: NotificationPreferences,
    private val repository: NotificationRepository,
) : ViewModel() {

    val uiState: StateFlow<NotificationSettingsUiState> = combine(
        preferences.enabled,
        preferences.mirrorToShade,
        preferences.autoClearDaysFlow,
        repository.observeAll(),
        repository.observeUnreadCount(),
    ) { enabled, mirror, days, rows, unread ->
        NotificationSettingsUiState(
            enabled = enabled,
            mirrorToShade = mirror,
            autoClearDays = days,
            storedCount = rows.size,
            unreadCount = unread,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NotificationSettingsUiState(),
    )

    fun setEnabled(enabled: Boolean) = viewModelScope.launch { preferences.setEnabled(enabled) }

    fun setMirrorToShade(mirror: Boolean) =
        viewModelScope.launch { preferences.setMirrorToShade(mirror) }

    /** Cycles the retention window, ending on Never so "keep everything" is reachable in one row. */
    fun cycleAutoClearDays() = viewModelScope.launch {
        val current = uiState.value.autoClearDays
        val next = AUTO_CLEAR_CHOICES.getOrNull(AUTO_CLEAR_CHOICES.indexOf(current) + 1)
            ?: AUTO_CLEAR_CHOICES.first()
        preferences.setAutoClearDays(next)
    }

    fun markAllRead() = viewModelScope.launch { repository.markAllRead() }

    /** Empties the history. Running work is untouched — it was never a row to begin with. */
    fun clearAll() = viewModelScope.launch { repository.clearAll() }

    companion object {
        /** 0 is last and means never; the cycle wraps back to 7 from there. */
        val AUTO_CLEAR_CHOICES = listOf(7, 14, 30, 90, 0)

        fun autoClearLabel(days: Int): String = when (days) {
            0 -> "Never"
            1 -> "1 Day"
            else -> "$days Days"
        }
    }
}
