package com.playfieldportal.feature.xmb.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Surfaces background work (ROM scans, artwork fetches, etc.) as Android system
 * notifications instead of an in-app tray. Each task is keyed by its string id so
 * progress updates replace the same notification rather than stacking.
 */
class BackgroundTaskNotifier(private val context: Context) {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background Tasks",
            NotificationManager.IMPORTANCE_LOW, // quiet — no sound/peek for progress
        ).apply {
            description = "ROM scans, artwork downloads and other background work"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** Post or update a running task. A null [progress] shows an indeterminate bar. */
    fun running(id: String, label: String, progress: Float?) {
        val builder = base(label)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
        if (progress == null) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
        }
        post(id, builder.build())
    }

    /** Mark a task finished — dismissible, no progress bar. */
    fun complete(id: String, label: String, message: String?) {
        val builder = base(label)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
        if (!message.isNullOrBlank()) builder.setContentText(message)
        post(id, builder.build())
    }

    /** Mark a task failed — dismissible, shows the error text. */
    fun failed(id: String, label: String, message: String) {
        val builder = base(label)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setOngoing(false)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .setContentText(message)
        post(id, builder.build())
    }

    fun cancel(id: String) = manager.cancel(id.notificationId())

    private fun base(label: String) = Notification.Builder(context, CHANNEL_ID)
        .setContentTitle(label)
        .setOnlyAlertOnce(true)

    private fun post(id: String, notification: Notification) {
        if (!canPost()) return
        manager.notify(id.notificationId(), notification)
    }

    // POST_NOTIFICATIONS is a runtime permission on API 33+. Without the grant,
    // notify() is silently dropped by the system, so skip rather than risk noise.
    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun String.notificationId(): Int = hashCode()

    companion object {
        private const val CHANNEL_ID = "pfp_background_tasks"
    }
}
