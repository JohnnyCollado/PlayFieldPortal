package com.playfieldportal.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.feature.achievements.api.SteamAchievementsApi
import com.playfieldportal.feature.achievements.match.AchievementAutoMatcher
import com.playfieldportal.feature.achievements.match.MatchReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    val isMatching: Boolean = false,
    val matchDone: Int = 0,
    val matchTotal: Int = 0,
    val matchReport: MatchReport? = null,
)

// Transient UI-only state (not backed by DataStore), folded into uiState.
private data class Extra(
    val message: String? = null,
    val isMatching: Boolean = false,
    val matchDone: Int = 0,
    val matchTotal: Int = 0,
    val matchReport: MatchReport? = null,
)

private val STEAM_ID64 = Regex("\\d{17}")
private val DATE_FMT = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US)

/**
 * Connect-accounts screen state. API keys are write-only: never read back into the UI (the fields
 * show a masked placeholder when configured), only the public identities are surfaced. Saving Steam
 * resolves a vanity name to a SteamID64 once and caches it. Also drives the batch auto-match.
 */
@HiltViewModel
class AchievementsSettingsViewModel @Inject constructor(
    private val credentials: AchievementCredentialsProvider,
    private val steamApi: SteamAchievementsApi,
    private val autoMatcher: AchievementAutoMatcher,
) : ViewModel() {

    private val extra = MutableStateFlow(Extra())

    val uiState: StateFlow<AchievementsSettingsUiState> = combine(
        credentials.raUsernameFlow,
        credentials.steamId64Flow,
        credentials.enabledFlow,
        credentials.lastSyncedAtFlow,
        extra,
    ) { raUser, steamId, enabled, lastSynced, ex ->
        AchievementsSettingsUiState(
            enabled = enabled,
            hasRetroAchievements = !raUser.isNullOrBlank(),
            raUsername = raUser.orEmpty(),
            hasSteam = !steamId.isNullOrBlank(),
            steamId64 = steamId.orEmpty(),
            lastSyncedLabel = lastSynced?.let { DATE_FMT.format(Date(it)) } ?: "Never",
            message = ex.message,
            isMatching = ex.isMatching,
            matchDone = ex.matchDone,
            matchTotal = ex.matchTotal,
            matchReport = ex.matchReport,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AchievementsSettingsUiState())

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { credentials.setEnabled(enabled) }
    }

    fun connectRetroAchievements(username: String, apiKey: String) {
        viewModelScope.launch {
            credentials.saveRetroAchievements(username, apiKey)
            extra.update { it.copy(message = "RetroAchievements connected") }
        }
    }

    fun disconnectRetroAchievements() {
        viewModelScope.launch {
            credentials.clearRetroAchievements()
            extra.update { it.copy(message = "RetroAchievements disconnected") }
        }
    }

    fun connectSteam(idOrVanity: String, apiKey: String) {
        viewModelScope.launch {
            val input = idOrVanity.trim()
            val key = apiKey.trim()
            credentials.saveSteam(input, key)
            if (input.matches(STEAM_ID64)) {
                extra.update { it.copy(message = "Steam connected") }
                return@launch
            }
            val resolved = steamApi.resolveVanity(input)
            if (resolved != null) {
                credentials.saveSteam(resolved, key)
                extra.update { it.copy(message = "Steam connected — resolved \"$input\"") }
            } else {
                extra.update { it.copy(message = "Key saved, but \"$input\" couldn't be resolved. Enter your SteamID64.") }
            }
        }
    }

    fun disconnectSteam() {
        viewModelScope.launch {
            credentials.clearSteam()
            extra.update { it.copy(message = "Steam disconnected") }
        }
    }

    /** Batch-links every unlinked game and surfaces a report of what couldn't be matched. */
    fun autoMatch() {
        if (extra.value.isMatching) return
        viewModelScope.launch {
            extra.update { it.copy(isMatching = true, matchReport = null, matchDone = 0, matchTotal = 0) }
            val report = autoMatcher.matchUnlinked { done, total ->
                extra.update { it.copy(matchDone = done, matchTotal = total) }
            }
            extra.update { it.copy(isMatching = false, matchReport = report) }
        }
    }

    fun dismissMessage() = extra.update { it.copy(message = null) }
    fun dismissReport() = extra.update { it.copy(matchReport = null) }
}
