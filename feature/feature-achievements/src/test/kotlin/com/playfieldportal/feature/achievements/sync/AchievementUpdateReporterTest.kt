package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.model.TaskKind
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

/**
 * Selective sync, Task 11: one stable tray task per update run. A manual run shows one running
 * item and settles into exactly one result; a quiet scheduled run with no changes leaves nothing
 * unread; failures and pauses are actionable and named without any key or URL.
 */
class AchievementUpdateReporterTest {

    private val tasks = mockk<BackgroundTaskCenter>(relaxed = true)
    private val reporter = AchievementUpdateReporter(tasks)
    private val id = AchievementUpdateReporter.UPDATE_TASK_ID

    private fun summary(
        total: Int = 5, checked: Int = 5, updated: Int = 0, unchanged: Int = 5, failed: Int = 0,
        cancelled: Boolean = false,
    ) = AchievementUpdateSummary(
        total = total, checked = checked, updated = updated, unchanged = unchanged,
        skipped = total - checked, failed = failed, cancelled = cancelled,
    )

    @Test
    fun `a manual run shows progress under one id and settles once`() {
        reporter.started(SyncTrigger.MANUAL)
        reporter.progress(SyncTrigger.MANUAL, 2, 5)
        reporter.finished(SyncTrigger.MANUAL, summary(updated = 2, unchanged = 3), newPauses = emptySet())

        verify(exactly = 1) { tasks.start(id, "Updating installed achievements", TaskKind.ACHIEVEMENT) }
        verify { tasks.progress(id, 2, 5, "Checking 2 of 5 games") }
        verify(exactly = 1) {
            tasks.report(
                id = id,
                label = "Achievements updated",
                message = "2 games updated · 3 unchanged",
                severity = NotificationSeverity.SUCCESS,
                kind = NotificationKind.ACHIEVEMENT,
                action = any(),
            )
        }
    }

    @Test
    fun `a manual run with no changes says the achievements are up to date`() {
        reporter.started(SyncTrigger.MANUAL)
        reporter.finished(SyncTrigger.MANUAL, summary(), newPauses = emptySet())

        verify {
            tasks.report(id, "Achievements are up to date", "5 installed games checked", NotificationSeverity.SUCCESS, NotificationKind.ACHIEVEMENT, any())
        }
    }

    @Test
    fun `a quiet scheduled run with no changes leaves nothing in the tray`() {
        reporter.started(SyncTrigger.AUTOMATIC)
        reporter.progress(SyncTrigger.AUTOMATIC, 1, 5)
        reporter.finished(SyncTrigger.AUTOMATIC, summary(), newPauses = emptySet())

        verify(exactly = 0) { tasks.start(any<String>(), any(), any(), any(), any()) }
        verify(exactly = 0) { tasks.report(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { tasks.complete(any(), any(), any()) }
    }

    @Test
    fun `a scheduled run that found updates leaves one result`() {
        reporter.finished(SyncTrigger.AUTOMATIC, summary(updated = 1, unchanged = 4), newPauses = emptySet())

        verify(exactly = 1) {
            tasks.report(id, "Achievements updated", "1 game updated · 4 unchanged", NotificationSeverity.SUCCESS, NotificationKind.ACHIEVEMENT, any())
        }
    }

    @Test
    fun `a partial failure keeps cached progress and asks for another try`() {
        reporter.finished(SyncTrigger.MANUAL, summary(updated = 1, unchanged = 2, failed = 2), newPauses = emptySet())

        verify {
            tasks.report(id, "Some achievements couldn't update", "2 of 5 games need another try", NotificationSeverity.WARNING, NotificationKind.ACHIEVEMENT, any())
        }
    }

    @Test
    fun `a cancelled manual run says progress was kept`() {
        reporter.finished(SyncTrigger.MANUAL, summary(checked = 3, cancelled = true), newPauses = emptySet())

        verify {
            tasks.report(id, "Achievement update stopped", "3 games checked; saved progress kept", NotificationSeverity.INFO, NotificationKind.ACHIEVEMENT, any())
        }
    }

    @Test
    fun `each new pause is one actionable item with its own id`() {
        reporter.finished(
            SyncTrigger.AUTOMATIC,
            summary(),
            newPauses = setOf(UpdatePause.Offline, UpdatePause.Credentials(AchievementProvider.RETRO_ACHIEVEMENTS), UpdatePause.SteamPrivate),
        )

        verify { tasks.report("achievement_paused_offline", "Achievement update paused", "Connect to the internet", NotificationSeverity.WARNING, NotificationKind.ACHIEVEMENT, any()) }
        verify { tasks.report("achievement_paused_credentials_RETRO_ACHIEVEMENTS", "Achievement update paused", "Reconnect RetroAchievements", NotificationSeverity.WARNING, NotificationKind.ACHIEVEMENT, any()) }
        verify { tasks.report("achievement_paused_steam_private", "Achievement update paused", "Steam Game Details aren't available", NotificationSeverity.WARNING, NotificationKind.ACHIEVEMENT, any()) }
    }

    @Test
    fun `recognized games get one grouped message only when there are some`() {
        reporter.gamesRecognized(0)
        verify(exactly = 0) { tasks.report(any(), any(), any(), any(), any(), any()) }

        reporter.gamesRecognized(3)
        verify(exactly = 1) {
            tasks.report(AchievementUpdateReporter.MATCH_TASK_ID, "Games recognized", "3 installed games matched. Updating their achievements.", NotificationSeverity.SUCCESS, NotificationKind.ACHIEVEMENT, any())
        }
    }

    @Test
    fun `a clear reports its result, and a failure offers a retry path`() {
        reporter.cleared(true)
        verify {
            tasks.report(AchievementUpdateReporter.CLEAR_TASK_ID, "Achievement records cleared", "Installed games need to be resynced to show achievements again", NotificationSeverity.SUCCESS, NotificationKind.ACHIEVEMENT, any())
        }

        reporter.cleared(false)
        verify {
            tasks.report(AchievementUpdateReporter.CLEAR_TASK_ID, "Couldn’t clear achievements", "Nothing was removed. Try again from Settings ▸ Achievements", NotificationSeverity.ERROR, NotificationKind.ACHIEVEMENT, any())
        }
    }
}
