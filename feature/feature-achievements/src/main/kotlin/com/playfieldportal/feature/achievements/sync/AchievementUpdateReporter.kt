package com.playfieldportal.feature.achievements.sync

import android.content.Context
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
import com.playfieldportal.feature.achievements.R
import dagger.hilt.android.qualifiers.ApplicationContext
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
    // Resolves the tray text in the user's language when each row is posted.
    @ApplicationContext private val context: Context,
) {
    /** A manual run shows a running row the panel can stop: stopping cancels the calling run. */
    suspend fun started(trigger: SyncTrigger) {
        if (trigger != SyncTrigger.MANUAL) return
        tasks.startStoppable(UPDATE_TASK_ID, context.getString(R.string.ach_upd_running), TaskKind.ACHIEVEMENT,
            stopNote = context.getString(R.string.ach_upd_running_stop_note))
    }

    fun progress(trigger: SyncTrigger, done: Int, total: Int) {
        if (trigger != SyncTrigger.MANUAL) return
        tasks.progress(UPDATE_TASK_ID, done, total, context.getString(R.string.ach_upd_progress, done, total))
    }

    fun finished(trigger: SyncTrigger, summary: AchievementUpdateSummary, newPauses: Set<UpdatePause>) {
        val manual = trigger == SyncTrigger.MANUAL
        when {
            summary.blocked -> if (manual) tasks.cancel(UPDATE_TASK_ID)
            summary.cancelled -> if (manual && tasks.isStopping(UPDATE_TASK_ID)) {
                // Stopped from the panel: the user is looking at it, so it settles quietly.
                tasks.stopped(
                    UPDATE_TASK_ID,
                    checkedKept(summary.checked),
                    OPEN_ACHIEVEMENTS, null, context.getString(R.string.ach_upd_stopped),
                )
            } else if (manual) {
                report(
                    UPDATE_TASK_ID, context.getString(R.string.ach_upd_stopped),
                    checkedKept(summary.checked),
                    NotificationSeverity.INFO, OPEN_ACHIEVEMENTS,
                )
            } else tasks.cancel(UPDATE_TASK_ID)
            summary.failed > 0 -> report(
                UPDATE_TASK_ID, context.getString(R.string.ach_upd_some_failed),
                context.resources.getQuantityString(R.plurals.ach_upd_failed_message, summary.total, summary.failed, summary.total),
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
                        labels = ResultsLabels(done = context.getString(R.string.ach_upd_results_done)),
                    )
                },
            )
            summary.updated > 0 -> report(
                UPDATE_TASK_ID, context.getString(R.string.ach_upd_updated),
                context.resources.getQuantityString(R.plurals.ach_upd_updated_message, summary.updated, summary.updated, summary.unchanged),
                NotificationSeverity.SUCCESS, OPEN_ACHIEVEMENTS,
            )
            manual && summary.pauses.isEmpty() -> report(
                UPDATE_TASK_ID, context.getString(R.string.ach_upd_up_to_date),
                context.resources.getQuantityString(R.plurals.ach_upd_up_to_date_message, summary.checked, summary.checked),
                NotificationSeverity.SUCCESS, OPEN_ACHIEVEMENTS,
            )
            // Quiet: nothing changed, or only a pause (reported on its own below).
            else -> tasks.cancel(UPDATE_TASK_ID)
        }
        newPauses.forEach { pause ->
            report(
                "achievement_paused_${pause.code}", context.getString(R.string.ach_upd_paused),
                pauseMessage(pause), NotificationSeverity.WARNING, OPEN_CONNECTIONS,
                detail = pauseNotes(pause),
            )
        }
    }

    suspend fun matchStarted() = tasks.startStoppable(MATCH_TASK_ID, context.getString(R.string.ach_match_running), TaskKind.ACHIEVEMENT,
        stopNote = context.getString(R.string.ach_match_running_stop_note))

    fun matchProgress(done: Int, total: Int) = tasks.progress(MATCH_TASK_ID, done, total)

    /** An auto-match the user stopped settles quietly; any other cancellation leaves no row. */
    fun matchStopped() = tasks.settleCancelled(MATCH_TASK_ID, context.getString(R.string.ach_match_stopped_note),
        OPEN_ACHIEVEMENTS, null, context.getString(R.string.ach_match_stopped))

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
            MATCH_TASK_ID, context.getString(R.string.ach_match_recognized),
            context.resources.getQuantityString(R.plurals.ach_match_recognized_message, count, count),
            NotificationSeverity.SUCCESS, OPEN_ACHIEVEMENTS,
        )
    }

    fun cleared(success: Boolean) {
        if (success) {
            report(
                CLEAR_TASK_ID, context.getString(R.string.ach_clear_done),
                context.getString(R.string.ach_clear_done_message),
                NotificationSeverity.SUCCESS, OPEN_ACHIEVEMENT_SETTINGS,
            )
        } else {
            report(
                CLEAR_TASK_ID, context.getString(R.string.ach_clear_failed),
                context.getString(R.string.ach_clear_failed_message),
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
            facts = listOf(NoteFact(context.getString(R.string.ach_upd_fact_service), providerName(pause.provider))),
        )
        UpdatePause.SteamPrivate -> NotificationDetail.notes(PfpErrorCode.AC_3003)
    }

    private fun pauseMessage(pause: UpdatePause): String = when (pause) {
        UpdatePause.Offline -> context.getString(R.string.ach_upd_pause_offline)
        is UpdatePause.Credentials -> context.getString(R.string.ach_upd_pause_reconnect, providerName(pause.provider))
        UpdatePause.SteamPrivate -> context.getString(R.string.ach_upd_pause_steam_private)
    }

    private fun providerName(provider: AchievementProvider): String = when (provider) {
        AchievementProvider.RETRO_ACHIEVEMENTS -> "RetroAchievements"
        AchievementProvider.STEAM, AchievementProvider.LOCAL_STEAM -> "Steam"
        AchievementProvider.VITA_TROPHY -> "PS Vita"
        AchievementProvider.PS3_TROPHY -> "PS3"
        AchievementProvider.X360_ACHIEVEMENT -> "Xbox 360"
    }

    private fun checkedKept(count: Int) =
        context.resources.getQuantityString(R.plurals.ach_upd_checked_kept, count, count)

    companion object {
        const val UPDATE_TASK_ID = "achievement_update"
        const val MATCH_TASK_ID = "achievement_match"
        const val CLEAR_TASK_ID = "achievement_clear"

        private val OPEN_ACHIEVEMENTS = NotificationAction.OpenCategory("achievements")
        private val OPEN_CONNECTIONS = NotificationAction.OpenSettingsScreen("settings_achievements_credentials")
        private val OPEN_ACHIEVEMENT_SETTINGS = NotificationAction.OpenSettingsScreen("settings_achievements")
    }
}
