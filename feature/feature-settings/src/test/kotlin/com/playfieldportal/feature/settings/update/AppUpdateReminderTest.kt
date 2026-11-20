package com.playfieldportal.feature.settings.update

import com.playfieldportal.core.data.update.AppUpdateCheckResult
import com.playfieldportal.core.data.update.AppUpdateChecker
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.model.appupdate.AppRelease
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class AppUpdateReminderTest {

    private val checker = mockk<AppUpdateChecker>()
    private val tasks = mockk<BackgroundTaskCenter>(relaxed = true)
    private val reminder = AppUpdateReminder(checker, tasks)

    // The usual case: no reminded update has just been installed.
    init {
        coEvery { checker.takeInstalledReminder() } returns null
    }

    private val release = AppRelease(
        version = "1.3.4",
        notesUrl = "https://github.com/JohnnyCollado/PlayFieldPortal/releases/tag/1.3.4",
    )

    @Test
    fun `a due reminder posts one panel row that opens System Update`() = runTest {
        coEvery { checker.check(manual = false) } returns AppUpdateCheckResult.UpdateAvailable
        coEvery { checker.takeReminder() } returns release

        reminder.checkAndRemind()

        // The check comes first, so a release published since the last visit is reminded now.
        coVerifyOrder {
            checker.check(manual = false)
            checker.takeReminder()
        }
        verify(exactly = 1) {
            tasks.report(
                id = AppUpdateReminder.TRAY_ID,
                label = "System Update",
                message = "Version 1.3.4 is available.",
                severity = NotificationSeverity.INFO,
                kind = NotificationKind.SYSTEM,
                action = NotificationAction.OpenSettingsScreen(AppUpdateReminder.SCREEN_ROUTE),
            )
        }
    }

    @Test
    fun `an installed update replaces its reminder with a read row`() = runTest {
        coEvery { checker.check(manual = false) } returns AppUpdateCheckResult.UpToDate
        coEvery { checker.takeInstalledReminder() } returns "1.3.4"
        coEvery { checker.takeReminder() } returns null

        reminder.checkAndRemind()

        // Same id, so it overwrites "Version 1.3.4 is available."; read, so it neither rings nor
        // counts as unread.
        verify(exactly = 1) {
            tasks.report(
                id = AppUpdateReminder.TRAY_ID,
                label = "System Update",
                message = "Version 1.3.4 is installed.",
                severity = NotificationSeverity.INFO,
                kind = NotificationKind.SYSTEM,
                action = NotificationAction.OpenSettingsScreen(AppUpdateReminder.SCREEN_ROUTE),
                read = true,
            )
        }
    }

    @Test
    fun `nothing is posted when no reminder is due`() = runTest {
        coEvery { checker.check(manual = false) } returns AppUpdateCheckResult.Skipped
        coEvery { checker.takeReminder() } returns null

        reminder.checkAndRemind()

        verify(exactly = 0) { tasks.report(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a failed check still reminds about an update found earlier`() = runTest {
        coEvery { checker.check(manual = false) } returns AppUpdateCheckResult.Offline
        coEvery { checker.takeReminder() } returns release

        reminder.checkAndRemind()

        verify(exactly = 1) { tasks.report(id = AppUpdateReminder.TRAY_ID, label = any(), message = any(), severity = any(), kind = any(), action = any(), detail = any(), read = any()) }
    }
}
