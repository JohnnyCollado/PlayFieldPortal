package com.playfieldportal.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.domain.achievement.CoinWallet
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.feature.achievements.provider.steam.SteamRemoteDataSource
import com.playfieldportal.feature.achievements.match.AchievementMatchAndUpdate
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
    val localSteamTrackingEnabled: Boolean = false,
    val goldbergInstallerEnabled: Boolean = false,
    val wallet: CoinWallet = CoinWallet.EMPTY,
    val hasRetroAchievements: Boolean = false,
    val raUsername: String = "",
    val hasSteam: Boolean = false,
    val steamId64: String = "",
    val lastSyncedLabel: String = "Never",
    // Match/update/clear/connection OUTCOMES are tray-only (row + shade + notification cue); the
    // screen keeps only live progress, the paused state and the clear confirmation.
    val isMatching: Boolean = false,
    val matchDone: Int = 0,
    val matchTotal: Int = 0,
    val isSyncing: Boolean = false,
    val syncDone: Int = 0,
    val syncTotal: Int = 0,
    /** Scheduled updates are paused after Clear all tracked achievements until a manual resync. */
    val updatesPaused: Boolean = false,
    /** The Clear all tracked achievements confirmation is open. */
    val confirmClearVisible: Boolean = false,
    val isClearing: Boolean = false,
)

// The four persisted account flows, folded together so the wallet flow fits combine's arity.
private data class Accounts(
    val raUsername: String?,
    val steamId64: String?,
    val enabled: Boolean,
    val localSteamEnabled: Boolean,
    val lastSyncedAt: Long?,
)

// Transient UI-only state (not backed by DataStore), folded into uiState.
private data class Extra(
    val isMatching: Boolean = false,
    val matchDone: Int = 0,
    val matchTotal: Int = 0,
    val isSyncing: Boolean = false,
    val syncDone: Int = 0,
    val syncTotal: Int = 0,
    val confirmClearVisible: Boolean = false,
    val isClearing: Boolean = false,
)

private val DATE_FMT = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US)

/**
 * Connect-accounts screen state. API keys are write-only: never read back into the UI (the fields
 * show a masked placeholder when configured), only the public identities are surfaced. Saving Steam
 * resolves a vanity name to a SteamID64 once and caches it. Also drives Auto-Match, "Update
 * installed achievements" (the selective update — never an account-wide import) and the
 * confirmed Clear all tracked achievements.
 */
@HiltViewModel
class AchievementsSettingsViewModel @Inject constructor(
    private val credentials: AchievementCredentialsProvider,
    private val steamApi: SteamRemoteDataSource,
    private val matchAndUpdate: AchievementMatchAndUpdate,
    private val repository: AchievementController,
    // Connection outcomes go to the tray; update and clear outcomes are reported by the selective
    // sync itself, so every entry point words them the same way.
    private val tasks: com.playfieldportal.core.ui.notification.BackgroundTaskCenter,
) : ViewModel() {

    private val extra = MutableStateFlow(Extra())

    private val accounts = combine(
        credentials.raUsernameFlow,
        credentials.steamId64Flow,
        credentials.enabledFlow,
        credentials.localSteamTrackingEnabledFlow,
        credentials.lastSyncedAtFlow,
    ) { raUser, steamId, enabled, localSteam, lastSynced ->
        Accounts(raUser, steamId, enabled, localSteam, lastSynced)
    }

    val uiState: StateFlow<AchievementsSettingsUiState> = combine(
        accounts,
        extra,
        repository.observeWallet(),
        credentials.goldbergInstallerEnabledFlow,
        repository.observeAutoUpdatesPaused(),
    ) { acc, ex, wallet, goldberg, paused ->
        AchievementsSettingsUiState(
            enabled = acc.enabled,
            localSteamTrackingEnabled = acc.localSteamEnabled,
            goldbergInstallerEnabled = goldberg,
            wallet = wallet,
            hasRetroAchievements = !acc.raUsername.isNullOrBlank(),
            raUsername = acc.raUsername.orEmpty(),
            hasSteam = !acc.steamId64.isNullOrBlank(),
            steamId64 = acc.steamId64.orEmpty(),
            lastSyncedLabel = acc.lastSyncedAt?.let { DATE_FMT.format(Date(it)) } ?: "Never",
            isMatching = ex.isMatching,
            matchDone = ex.matchDone,
            matchTotal = ex.matchTotal,
            isSyncing = ex.isSyncing,
            syncDone = ex.syncDone,
            syncTotal = ex.syncTotal,
            updatesPaused = paused,
            confirmClearVisible = ex.confirmClearVisible,
            isClearing = ex.isClearing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AchievementsSettingsUiState())

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { credentials.setEnabled(enabled) }
    }

    /**
     * Opts into (or out of) tracking emulated Local Steam games. Enabling is destructive-adjacent —
     * it lets a later sync rewrite emu configs and swap the steam_api DLL — so the screen gates the
     * on-transition behind a save-backup warning and only calls this once the user confirms.
     */
    fun setLocalSteamTracking(enabled: Boolean) {
        viewModelScope.launch { credentials.setLocalSteamTrackingEnabled(enabled) }
    }

    /**
     * Opts into (or out of) the Goldberg installer: when on, a scan offers to convert detected emu
     * games (write their achievement data and swap in the emu DLL). Like tracking, enabling rewrites
     * game folders, so the screen gates the on-transition behind the same save-backup warning.
     */
    fun setGoldbergInstaller(enabled: Boolean) {
        viewModelScope.launch { credentials.setGoldbergInstallerEnabled(enabled) }
    }

    /**
     * Records a connection outcome in the tray (row + shade + notification cue) — no in-screen row.
     *
     * "Steam connected" is worth finding again an hour later, which is what the tray is for; a
     * dismissible row that dies with the screen is exactly what the user asked to stop seeing here.
     */
    private fun announce(
        id: String,
        message: String,
        severity: NotificationSeverity = NotificationSeverity.INFO,
    ) {
        tasks.report(
            id = id,
            label = message,
            severity = severity,
            kind = NotificationKind.ACHIEVEMENT,
            action = NotificationAction.OpenSettingsScreen("settings_achievements_credentials"),
        )
    }

    fun connectRetroAchievements(username: String, apiKey: String) {
        viewModelScope.launch {
            credentials.saveRetroAchievements(username, apiKey)
            announce("ra_connection", "RetroAchievements connected", NotificationSeverity.SUCCESS)
        }
    }

    fun disconnectRetroAchievements() {
        viewModelScope.launch {
            credentials.clearRetroAchievements()
            announce("ra_connection", "RetroAchievements disconnected")
        }
    }

    fun connectSteam(idOrVanity: String, apiKey: String) {
        viewModelScope.launch {
            val message = ServiceConnectors.connectSteam(credentials, steamApi, idOrVanity, apiKey)
            announce("steam_connection", message)
        }
    }

    fun disconnectSteam() {
        viewModelScope.launch {
            credentials.clearSteam()
            announce("steam_connection", "Steam disconnected")
        }
    }

    /**
     * Auto-Match, then "Update installed achievements" in the same pass so freshly matched games
     * get their coins. Only games on this device are matched; the update fetches the new matches
     * first. One run at a time, and never while a clear is in progress.
     */
    fun autoMatch() {
        val ex = extra.value
        if (ex.isMatching || ex.isSyncing || ex.isClearing) return
        viewModelScope.launch {
            extra.update { it.copy(isMatching = true, matchDone = 0, matchTotal = 0) }
            try {
                matchAndUpdate.run(
                    onMatchProgress = { done, total -> extra.update { it.copy(matchDone = done, matchTotal = total) } },
                    onUpdateProgress = { done, total ->
                        extra.update { it.copy(isMatching = false, isSyncing = true, syncDone = done, syncTotal = total) }
                    },
                )
            } finally {
                extra.update { it.copy(isMatching = false, isSyncing = false) }
            }
        }
    }

    /**
     * "Update installed achievements": the selective update of present, matched games — the only
     * manual refresh, and the action that resumes scheduled updates after a clear. Progress stays
     * on screen; the result goes to the tray.
     */
    fun updateInstalledAchievements() {
        val ex = extra.value
        if (ex.isSyncing || ex.isMatching || ex.isClearing) return
        viewModelScope.launch {
            extra.update { it.copy(isSyncing = true, syncDone = 0, syncTotal = 0) }
            try {
                repository.updateInstalledAchievements { done, total ->
                    extra.update { it.copy(syncDone = done, syncTotal = total) }
                }
            } finally {
                extra.update { it.copy(isSyncing = false) }
            }
        }
    }

    /** Stops a running update; everything saved so far is kept. */
    fun cancelUpdate() = repository.cancelUpdate()

    // ── Clear all tracked achievements ─────────────────────────────────────────

    /** Opens the confirmation. Nothing is touched until [confirmClearAll]. */
    fun requestClearAll() {
        if (extra.value.isClearing) return
        extra.update { it.copy(confirmClearVisible = true) }
    }

    /** Cancel, Back or a tap outside: closes the confirmation and changes nothing. */
    fun dismissClearAll() = extra.update { it.copy(confirmClearVisible = false) }

    /**
     * Clears every achievement record PFP stores after the user confirmed. Running achievement
     * work is cancelled first; the result (or failure) is reported in the tray.
     */
    fun confirmClearAll() {
        val ex = extra.value
        if (!ex.confirmClearVisible || ex.isClearing) return
        extra.update { it.copy(confirmClearVisible = false, isClearing = true) }
        viewModelScope.launch {
            try {
                repository.clearAllTrackedAchievements()
            } finally {
                extra.update { it.copy(isClearing = false) }
            }
        }
    }
}
