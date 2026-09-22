package com.playfieldportal.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.domain.achievement.CoinWallet
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.model.TaskKind
import com.playfieldportal.feature.achievements.BatchSyncResult
import com.playfieldportal.feature.achievements.notificationLine
import android.content.Context
import com.playfieldportal.feature.achievements.RaAccountImporter
import com.playfieldportal.feature.achievements.RaImportResult
import com.playfieldportal.feature.achievements.SteamImportWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import com.playfieldportal.feature.achievements.provider.steam.SteamRemoteDataSource
import com.playfieldportal.feature.achievements.match.AchievementAutoMatcher
import com.playfieldportal.feature.achievements.match.MatchReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
    val message: String? = null,
    val isMatching: Boolean = false,
    val matchDone: Int = 0,
    val matchTotal: Int = 0,
    val matchReport: MatchReport? = null,
    val isSyncing: Boolean = false,
    val syncDone: Int = 0,
    val syncTotal: Int = 0,
    val syncResult: BatchSyncResult? = null,
    val isImporting: Boolean = false,
    val importDone: Int = 0,
    val importTotal: Int = 0,
    val importResult: RaImportResult? = null,
    val isSteamImporting: Boolean = false,
    val steamImportDone: Int = 0,
    val steamImportTotal: Int = 0,
    val steamImportSummary: String? = null,
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
    val message: String? = null,
    val isMatching: Boolean = false,
    val matchDone: Int = 0,
    val matchTotal: Int = 0,
    val matchReport: MatchReport? = null,
    val isSyncing: Boolean = false,
    val syncDone: Int = 0,
    val syncTotal: Int = 0,
    val syncResult: BatchSyncResult? = null,
    val isImporting: Boolean = false,
    val importDone: Int = 0,
    val importTotal: Int = 0,
    val importResult: RaImportResult? = null,
    val isSteamImporting: Boolean = false,
    val steamImportDone: Int = 0,
    val steamImportTotal: Int = 0,
    val steamImportSummary: String? = null,
)

private val DATE_FMT = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US)

/**
 * Connect-accounts screen state. API keys are write-only: never read back into the UI (the fields
 * show a masked placeholder when configured), only the public identities are surfaced. Saving Steam
 * resolves a vanity name to a SteamID64 once and caches it. Also drives the batch auto-match.
 */
@HiltViewModel
class AchievementsSettingsViewModel @Inject constructor(
    private val credentials: AchievementCredentialsProvider,
    private val steamApi: SteamRemoteDataSource,
    private val autoMatcher: AchievementAutoMatcher,
    private val repository: AchievementController,
    private val raImporter: RaAccountImporter,
    // Settings has always shown these outcomes as a dismissible row that vanishes with the screen.
    // The tray is where they survive: the same run started from the Shiba Coins hub already lands
    // there, and a result the user has to be looking at Settings to ever see is the one they miss.
    private val tasks: com.playfieldportal.core.ui.notification.BackgroundTaskCenter,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val extra = MutableStateFlow(Extra())

    init {
        // The Steam import is a WorkManager job (a large library takes 10-20 minutes) — this
        // observer is the single source of its in-app state, so reopening the screen mid-import
        // reattaches to the live run. getInstance is guarded because plain JVM unit tests have
        // no WorkManager initialized.
        runCatching { androidx.work.WorkManager.getInstance(context) }.getOrNull()?.let { wm ->
            viewModelScope.launch {
                wm.getWorkInfosForUniqueWorkFlow(SteamImportWorker.UNIQUE_NAME)
                    .collect { infos -> onSteamImportWorkInfos(infos) }
            }
        }
    }

    private fun onSteamImportWorkInfos(infos: List<androidx.work.WorkInfo>) {
        val active = infos.firstOrNull { !it.state.isFinished }
        if (active != null) {
            val p = active.progress
            extra.update {
                it.copy(
                    isSteamImporting = true,
                    steamImportSummary = null,
                    steamImportDone = p.getInt(SteamImportWorker.KEY_DONE, 0),
                    steamImportTotal = p.getInt(SteamImportWorker.KEY_TOTAL, 0),
                )
            }
            return
        }
        if (!extra.value.isSteamImporting) return
        val finished = infos.maxByOrNull { it.state.ordinal }
        val summary = when (finished?.state) {
            androidx.work.WorkInfo.State.SUCCEEDED -> steamSummaryOf(finished.outputData)
            androidx.work.WorkInfo.State.CANCELLED -> "Import cancelled — progress so far is kept"
            else -> "Import failed — run again to resume"
        }
        extra.update { it.copy(isSteamImporting = false, steamImportSummary = summary) }
    }

    private fun steamSummaryOf(out: androidx.work.Data): String = when {
        out.getBoolean(SteamImportWorker.KEY_MISSING_CREDENTIALS, false) ->
            "Steam needs credentials — connect your account first"
        out.getBoolean(SteamImportWorker.KEY_PROFILE_NOT_PUBLIC, false) ->
            "Your Steam profile's Game Details are private"
        else -> buildString {
            append("${out.getInt(SteamImportWorker.KEY_IMPORTED, 0)} imported")
            out.getInt(SteamImportWorker.KEY_NO_COINS, 0).takeIf { it > 0 }
                ?.let { append(" · $it without achievements") }
            out.getInt(SteamImportWorker.KEY_NO_PROGRESS, 0).takeIf { it > 0 }
                ?.let { append(" · $it not played yet") }
            out.getInt(SteamImportWorker.KEY_FAILED, 0).takeIf { it > 0 }
                ?.let { append(" · $it failed") }
        }
    }

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
    ) { acc, ex, wallet, goldberg ->
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
            message = ex.message,
            isMatching = ex.isMatching,
            matchDone = ex.matchDone,
            matchTotal = ex.matchTotal,
            matchReport = ex.matchReport,
            isSyncing = ex.isSyncing,
            syncDone = ex.syncDone,
            syncTotal = ex.syncTotal,
            syncResult = ex.syncResult,
            isImporting = ex.isImporting,
            importDone = ex.importDone,
            importTotal = ex.importTotal,
            importResult = ex.importResult,
            isSteamImporting = ex.isSteamImporting,
            steamImportDone = ex.steamImportDone,
            steamImportTotal = ex.steamImportTotal,
            steamImportSummary = ex.steamImportSummary,
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
     * Raises a settings toast AND records it in the tray.
     *
     * The dismissible row is the right thing while the user is on this screen; it dies with the
     * screen. "Steam connected" is worth finding again an hour later, which is what the tray is
     * for — so every toast goes to both, from one place, rather than each call site remembering.
     */
    private fun announce(
        id: String,
        message: String,
        severity: NotificationSeverity = NotificationSeverity.INFO,
    ) {
        extra.update { it.copy(message = message) }
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

    private var syncJob: Job? = null

    /**
     * Batch-links every unlinked game and surfaces a report of what couldn't be matched, then
     * immediately syncs all linked games so freshly matched games get their coin data in the same
     * pass (a match without a sync would leave every new link at zero coins).
     *
     * Matching always runs first: a sync already in flight is cancelled and re-run in full after
     * the match, so a long-running sync can never block or delay auto-matching.
     */
    fun autoMatch() {
        if (extra.value.isMatching) return
        viewModelScope.launch {
            syncJob?.cancelAndJoin()
            extra.update { it.copy(isMatching = true, matchReport = null, matchDone = 0, matchTotal = 0) }
            // Same task id the hub uses: it is the same operation, so it is one tray row either
            // way rather than one per entry point.
            tasks.start(MATCH_TASK_ID, "Auto-matching games", TaskKind.ACHIEVEMENT)
            val report = autoMatcher.matchUnlinked { done, total ->
                tasks.progress(MATCH_TASK_ID, done, total)
                extra.update { it.copy(matchDone = done, matchTotal = total) }
            }
            tasks.complete(
                MATCH_TASK_ID,
                report.notificationLine(),
                NotificationAction.OpenCategory("achievements"),
            )
            extra.update { it.copy(isMatching = false, matchReport = report) }
            launchSyncAll().join()
        }
    }

    /** Refreshes coin data for every linked game at once, with a progress + result summary. */
    fun syncAll() {
        if (extra.value.isSyncing || extra.value.isMatching) return
        launchSyncAll()
    }

    private fun launchSyncAll(): Job {
        val job = viewModelScope.launch {
            try {
                extra.update { it.copy(isSyncing = true, syncResult = null, syncDone = 0, syncTotal = 0) }
                tasks.start(SYNC_TASK_ID, "Syncing Shiba Coins", TaskKind.ACHIEVEMENT)
                val result = repository.syncAllLinked { done, total ->
                    tasks.progress(SYNC_TASK_ID, done, total)
                    extra.update { it.copy(syncDone = done, syncTotal = total) }
                }
                // Missing credentials is a WARNING, not a success: "41 synced" over silently
                // skipped providers is how a missing API key goes unnoticed.
                if (result.missingCredentials) {
                    tasks.report(
                        id = SYNC_TASK_ID,
                        label = "Syncing Shiba Coins",
                        message = result.notificationLine(),
                        severity = NotificationSeverity.WARNING,
                        kind = NotificationKind.ACHIEVEMENT,
                        action = NotificationAction.OpenSettingsScreen("settings_achievements_credentials"),
                    )
                } else {
                    tasks.complete(
                        SYNC_TASK_ID,
                        result.notificationLine(),
                        NotificationAction.OpenCategory("achievements"),
                    )
                }
                extra.update { it.copy(isSyncing = false, syncResult = result) }
            } finally {
                // A cancelled sync (auto-match taking over) must not leave the spinner stuck, or
                // a RUNNING row behind in the tray with nothing alive to finish it.
                if (extra.value.isSyncing) {
                    tasks.cancel(SYNC_TASK_ID)
                    extra.update { it.copy(isSyncing = false) }
                }
            }
        }
        syncJob = job
        return job
    }

    /**
     * Imports the account's whole RetroAchievements history as tracked entries. Resumable: an
     * interrupted run's next attempt continues with the entries still missing coin detail.
     */
    fun importRaHistory() {
        if (extra.value.isImporting || extra.value.isSyncing || extra.value.isMatching) return
        viewModelScope.launch {
            try {
                extra.update { it.copy(isImporting = true, importResult = null, importDone = 0, importTotal = 0) }
                val result = raImporter.import { done, total ->
                    extra.update { it.copy(importDone = done, importTotal = total) }
                }
                extra.update { it.copy(isImporting = false, importResult = result) }
            } finally {
                if (extra.value.isImporting) extra.update { it.copy(isImporting = false) }
            }
        }
    }

    /** Enqueues the Steam library import as background work (no-op if one is running). */
    fun importSteamLibrary() {
        SteamImportWorker.enqueue(context)
    }

    fun cancelSteamImport() {
        SteamImportWorker.cancel(context)
    }

    private companion object {
        // The XMB's Shiba Coins hub runs the same two operations under these ids, so whichever
        // surface starts a run, it is one tray row rather than one per entry point.
        const val MATCH_TASK_ID = "achievement_match"
        const val SYNC_TASK_ID = "achievement_sync"
    }

    fun dismissMessage() = extra.update { it.copy(message = null) }
    fun dismissReport() = extra.update { it.copy(matchReport = null) }
    fun dismissSyncResult() = extra.update { it.copy(syncResult = null) }
    fun dismissImportResult() = extra.update { it.copy(importResult = null) }
    fun dismissSteamImportSummary() = extra.update { it.copy(steamImportSummary = null) }
}
