package com.playfieldportal.feature.settings.update

import com.playfieldportal.core.data.update.AppUpdateChecker
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the user about a newer app release each time the launcher comes back to the front.
 *
 * Both halves are rate-limited inside [AppUpdateChecker], so calling this on every resume is cheap:
 * the network check runs at most once a day, and the panel row is posted once per release and then
 * at most once a day. Posting more often would mark the row unread and play the notification sound
 * after every game.
 */
@Singleton
class AppUpdateReminder @Inject constructor(
    private val checker: AppUpdateChecker,
    private val tasks: BackgroundTaskCenter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onResume() {
        scope.launch {
            try {
                checkAndRemind()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "App update reminder failed")
            }
        }
    }

    internal suspend fun checkAndRemind() {
        checker.check(manual = false)
        // The update a row was posted for is now the running build: overwrite that row (same id)
        // so the panel stops offering it. Read, so it neither rings nor counts as unread.
        checker.takeInstalledReminder()?.let { version ->
            tasks.report(
                id = TRAY_ID,
                label = "System Update",
                message = "Version $version is installed.",
                severity = NotificationSeverity.INFO,
                kind = NotificationKind.SYSTEM,
                action = NotificationAction.OpenSettingsScreen(SCREEN_ROUTE),
                read = true,
            )
        }
        val release = checker.takeReminder() ?: return
        tasks.report(
            id = TRAY_ID,
            label = "System Update",
            message = "Version ${release.version} is available.",
            severity = NotificationSeverity.INFO,
            kind = NotificationKind.SYSTEM,
            action = NotificationAction.OpenSettingsScreen(SCREEN_ROUTE),
        )
    }

    companion object {
        const val TRAY_ID = "system_update"
        const val SCREEN_ROUTE = "settings_system_update"
    }
}
