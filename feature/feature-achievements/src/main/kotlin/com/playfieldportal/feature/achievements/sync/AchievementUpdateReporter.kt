package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NoteFact
import com.playfieldportal.core.domain.model.NotificationDetail
import com.playfieldportal.core.domain.model.PfpErrorCode
import com.playfieldportal.core.domain.model.ResultItem
import com.playfieldportal.core.domain.model.ResultOutcome
import com.playfieldportal.core.domain.model.ResultsLabels
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.model.TaskKind
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every achievement-update message the notification tray shows (plan Task 11), through the one
 * [BackgroundTaskCenter].
 *
 * One stable task id per run: a manual run shows a single running item that settles into exactly
 * one result (a settled row replaces the previous one with the same id). A scheduled run shows no
 * running item and leaves a result only when something actually changed or failed — a quiet,
 * unchanged check never creates an unread row. Pauses get one item per distinct condition; the
 * coordinator passes only conditions that are new, so a recurring scheduled failure is not
 * repeated. Nothing here claims "new achievements unlocked", and no key or URL is ever included.
 */
@Singleton
class AchievementUpdateReporter @Inject constructor(
    private val tasks: BackgroundTaskCenter,
) {
    /** A manual run shows a running row the panel can stop: stopping cancels the calling run. */
    suspend fun started(trigger: SyncTrigger) {
        if (trigger != SyncTrigger.MANUAL) return
        tasks.startStoppable(UPDATE_TASK_ID, "Updating installed achievements", TaskKind.ACHIEVEMENT,
            stopNote = "Achievements already updated are kept.")
    }

    fun progress(trigger: SyncTrigger, done: Int, total: Int) {
        if (trigger != SyncTrigger.MANUAL) return
        tasks.progress(UPDATE_TASK_ID, done, total, "Checking $done of $total games")
    }

    fun finished(trigger: SyncTrigger, summary: AchievementUpdateSummary, newPauses: Set<UpdatePause>) {
        val manual = trigger == SyncTrigger.MANUAL
        when {
            summary.blocked -> if (manual) tasks.cancel(UPDATE_TASK_ID)
            summary.cancelled -> if (manual && tasks.isStopping(UPDATE_TASK_ID)) {
                // Stopped from the panel: the user is looking at it, so it settles quietly.
                tasks.stopped(
                    UPDATE_TASK_ID,
                    "${summary.checked} ${games(summary.checked)} checked; saved progress kept",
                    OPEN_ACHIEVEMENTS, null, "Achievement update stopped",
                )
            } else if (manual) {
                report(
                    UPDATE_TASK_ID, "Achievement update stopped",
                    "${summary.checked} ${games(summary.checked)} checked; saved progress kept",
                    NotificationSeverity.INFO, OPEN_ACHIEVEMENTS,
                )
            } else tasks.cancel(UPDATE_TASK_ID)
            summary.failed > 0 -> report(
                UPDATE_TASK_ID, "Some achievements couldn't update",
                "${summary.failed} of ${summary.total} games need another try",
                NotificationSeverity.WARNING, OPEN_ACHIEVEMENTS,
                detail = summary.failedGames.takeIf { it.isNotEmpty() }?.let { failed ->
                    NotificationDetail.results(
                        failed.map { game ->
                            ResultItem(
                                primary = game.title,
                                outcome = ResultOutcome.FAILED,
                                reason = game.reason,
                                code = PfpErrorCode.AC_3004.id,
                            )
                        },
                        labels = ResultsLabels(done = "Updated"),
                    )
                },
            )
            summary.updated > 0 -> report(
                UPDATE_TASK_ID, "Achievements updated",
                "${summary.updated} ${games(summary.updated)} updated · ${summary.unchanged} unchanged",
                NotificationSeverity.SUCCESS, OPEN_ACHIEVEMENTS,
            )
            manual && summary.pauses.isEmpty() -> report(
                UPDATE_TASK_ID, "Achievements are up to date",
                "${summary.checked} installed ${games(summary.checked)} checked",
                NotificationSeverity.SUCCESS, OPEN_ACHIEVEMENTS,
            )
            // Quiet: nothing changed, or only a pause (reported on its own below).
            else -> tasks.cancel(UPDATE_TASK_ID)
        }
        newPauses.forEach { pause ->
            report(
                "achievement_paused_${pause.code}", "Achievement update paused",
                pauseMessage(pause), NotificationSeverity.WARNING, OPEN_CONNECTIONS,
                detail = pauseNotes(pause),
            )
        }
    }

    suspend fun matchStarted() = tasks.startStoppable(MATCH_TASK_ID, "Auto-matching games", TaskKind.ACHIEVEMENT,
        stopNote = "Games already matched stay linked.")

    fun matchProgress(done: Int, total: Int) = tasks.progress(MATCH_TASK_ID, done, total)

    /** An auto-match the user stopped settles quietly; any other cancellation leaves no row. */
    fun matchStopped() = tasks.settleCancelled(MATCH_TASK_ID, "Games already matched stay linked",
        OPEN_ACHIEVEMENTS, null, "Auto-match stopped")

    /**
     * One grouped message per auto-match run, only when it linked anything; a run that matched
     * nothing leaves no row (each game's reason is in the Untracked view).
     */
    fun gamesRecognized(count: Int) {
        if (count <= 0) {
            tasks.cancel(MATCH_TASK_ID)
            return
        }
        report(
            MATCH_TASK_ID, "Games recognized",
            "$count installed ${games(count)} matched. Updating their achievements.",
            NotificationSeverity.SUCCESS, OPEN_ACHIEVEMENTS,
        )
    }

    fun cleared(success: Boolean) {
        if (success) {
            report(
                CLEAR_TASK_ID, "Achievement records cleared",
                "Installed games need to be resynced to show achievements again",
                NotificationSeverity.SUCCESS, OPEN_ACHIEVEMENT_SETTINGS,
            )
        } else {
            report(
                CLEAR_TASK_ID, "Couldn’t clear achievements",
                "Nothing was removed. Try again from Settings ▸ Achievements",
                NotificationSeverity.ERROR, OPEN_ACHIEVEMENT_SETTINGS,
            )
        }
    }

    private fun report(
        id: String,
        label: String,
        message: String,
        severity: NotificationSeverity,
        action: NotificationAction,
        detail: NotificationDetail? = null,
    ) = tasks.report(
        id = id,
        label = label,
        message = message,
        severity = severity,
        kind = NotificationKind.ACHIEVEMENT,
        action = action,
        detail = detail,
    )

    /** A pause, as a note with its registry code; the provider is named as a fact. */
    private fun pauseNotes(pause: UpdatePause): NotificationDetail = when (pause) {
        UpdatePause.Offline -> NotificationDetail.notes(PfpErrorCode.AC_3001)
        is UpdatePause.Credentials -> NotificationDetail.notes(
            PfpErrorCode.AC_3002,
            facts = listOf(NoteFact("Service", providerName(pause.provider))),
        )
        UpdatePause.SteamPrivate -> NotificationDetail.notes(PfpErrorCode.AC_3003)
    }

    private fun pauseMessage(pause: UpdatePause): String = when (pause) {
        UpdatePause.Offline -> "Connect to the internet"
        is UpdatePause.Credentials -> "Reconnect ${providerName(pause.provider)}"
        UpdatePause.SteamPrivate -> "Steam Game Details aren't available"
    }

    private fun providerName(provider: AchievementProvider): String = when (provider) {
        AchievementProvider.RETRO_ACHIEVEMENTS -> "RetroAchievements"
        AchievementProvider.STEAM, AchievementProvider.LOCAL_STEAM -> "Steam"
        AchievementProvider.VITA_TROPHY -> "PS Vita"
        AchievementProvider.PS3_TROPHY -> "PS3"
        AchievementProvider.X360_ACHIEVEMENT -> "Xbox 360"
    }

    private fun games(count: Int) = if (count == 1) "game" else "games"

    companion object {
        const val UPDATE_TASK_ID = "achievement_update"
        const val MATCH_TASK_ID = "achievement_match"
        const val CLEAR_TASK_ID = "achievement_clear"

        private val OPEN_ACHIEVEMENTS = NotificationAction.OpenCategory("achievements")
        private val OPEN_CONNECTIONS = NotificationAction.OpenSettingsScreen("settings_achievements_credentials")
        private val OPEN_ACHIEVEMENT_SETTINGS = NotificationAction.OpenSettingsScreen("settings_achievements")
    }
}
