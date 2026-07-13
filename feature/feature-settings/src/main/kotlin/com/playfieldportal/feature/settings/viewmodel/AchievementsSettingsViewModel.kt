package com.playfieldportal.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.feature.achievements.api.SteamAchievementsApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class AchievementsSettingsUiState(
    val enabled: Boolean = false,
    val hasRetroAchievements: Boolean = false,
    val raUsername: String = "",
    val hasSteam: Boolean = false,
    val steamId64: String = "",
    val lastSyncedLabel: String = "Never",
    val message: String? = null,
)

private val STEAM_ID64 = Regex("\\d{17}")
private val DATE_FMT = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US)

/**
 * Connect-accounts screen state. API keys are write-only: they are never read back into the UI (the
 * fields show a masked placeholder when configured), and only the public identities — RA username,
 * SteamID64 — are surfaced. Saving Steam resolves a vanity name to a SteamID64 once and caches it.
 */
@HiltViewModel
class AchievementsSettingsViewModel @Inject constructor(
    private val credentials: AchievementCredentialsProvider,
    private val steamApi: SteamAchievementsApi,
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AchievementsSettingsUiState> = combine(
        credentials.raUsernameFlow,
        credentials.steamId64Flow,
        credentials.enabledFlow,
        credentials.lastSyncedAtFlow,
        message,
    ) { raUser, steamId, enabled, lastSynced, msg ->
        AchievementsSettingsUiState(
            enabled = enabled,
            // Username and key are always saved together, so a present identity means a present key.
            hasRetroAchievements = !raUser.isNullOrBlank(),
            raUsername = raUser.orEmpty(),
            hasSteam = !steamId.isNullOrBlank(),
            steamId64 = steamId.orEmpty(),
            lastSyncedLabel = lastSynced?.let { DATE_FMT.format(Date(it)) } ?: "Never",
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AchievementsSettingsUiState())

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { credentials.setEnabled(enabled) }
    }

    fun connectRetroAchievements(username: String, apiKey: String) {
        viewModelScope.launch {
            credentials.saveRetroAchievements(username, apiKey)
            message.value = "RetroAchievements connected"
        }
    }

    fun disconnectRetroAchievements() {
        viewModelScope.launch {
            credentials.clearRetroAchievements()
            message.value = "RetroAchievements disconnected"
        }
    }

    fun connectSteam(idOrVanity: String, apiKey: String) {
        viewModelScope.launch {
            val input = idOrVanity.trim()
            val key = apiKey.trim()
            // Save first so resolveVanity can authenticate with the new key.
            credentials.saveSteam(input, key)
            if (input.matches(STEAM_ID64)) {
                message.value = "Steam connected"
                return@launch
            }
            val resolved = steamApi.resolveVanity(input)
            if (resolved != null) {
                credentials.saveSteam(resolved, key)
                message.value = "Steam connected — resolved \"$input\""
            } else {
                message.value = "Key saved, but \"$input\" couldn't be resolved. Enter your SteamID64."
            }
        }
    }

    fun disconnectSteam() {
        viewModelScope.launch {
            credentials.clearSteam()
            message.value = "Steam disconnected"
        }
    }

    fun dismissMessage() { message.value = null }
}
