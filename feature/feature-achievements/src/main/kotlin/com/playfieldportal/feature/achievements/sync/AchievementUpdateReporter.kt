package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.model.NotificationAction
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
    fun started(trigger: SyncTrigger) {
        if (trigger != SyncTrigger.MANUAL) return
        tasks.start(UPDATE_TASK_ID, "Updating installed achievements", TaskKind.ACHIEVEMENT)
    }

    fun progress(trigger: SyncTrigger, done: Int, total: Int) {
        if (trigger != SyncTrigger.MANUAL) return
        tasks.progress(UPDATE_TASK_ID, done, total, "Checking $done of $total games")
    }

    fun finished(trigger: SyncTrigger, summary: AchievementUpdateSummary, newPauses: Set<UpdatePause>) {
        val manual = trigger == SyncTrigger.MANUAL
        when {
            summary.blocked -> if (manual) tasks.cancel(UPDATE_TASK_ID)
            summary.cancelled -> if (manual) {
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
            )
        }
    }

    fun matchStarted() = tasks.start(MATCH_TASK_ID, "Auto-matching games", TaskKind.ACHIEVEMENT)

    fun matchProgress(done: Int, total: Int) = tasks.progress(MATCH_TASK_ID, done, total)

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
    ) = tasks.report(
        id = id,
        label = label,
        message = message,
        severity = severity,
        kind = NotificationKind.ACHIEVEMENT,
        action = action,
    )

    private fun pauseMessage(pause: UpdatePause): String = when (pause) {
        UpdatePause.Offline -> "Connect to the internet"
        is UpdatePause.Credentials -> "Reconnect ${providerName(pause.provider)}"
        UpdatePause.SteamPrivate -> "Steam Game Details aren't available"
    }

    private fun providerName(provider: AchievementProvider): String = when (provider) {
        AchievementProvider.RETRO_ACHIEVEMENTS -> "RetroAchievements"
        AchievementProvider.STEAM, AchievementProvider.LOCAL_STEAM -> "Steam"
        AchievementProvider.VITA_TROPHY -> "PS Vita"
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
