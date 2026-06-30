package com.playfieldportal.launcher.receiver

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.playfieldportal.core.common.security.ShortcutIntentSanitizer
import timber.log.Timber

/**
 * Captures the legacy `com.android.launcher.action.INSTALL_SHORTCUT` broadcast.
 *
 * Apps like BannerHub and older Winlator builds still create game shortcuts by sending this
 * broadcast. The broadcast is unauthenticated — any app can send it — so PFP does NOT add the
 * shortcut silently. Instead it hardens the supplied intent ([ShortcutIntentSanitizer]) and posts a
 * confirmation notification; only when the user taps "Add" does [ShortcutConfirmReceiver] (which is
 * NOT exported) actually create the library entry. This prevents both confused-deputy abuse and
 * silent library poisoning.
 */
class InstallShortcutReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_SHORTCUT) return

        @Suppress("DEPRECATION")
        val rawLaunch = intent.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT) ?: run {
            Timber.w("INSTALL_SHORTCUT received with no EXTRA_SHORTCUT_INTENT — ignoring")
            return
        }
        val launch = ShortcutIntentSanitizer.sanitize(rawLaunch, context.packageManager) ?: run {
            Timber.w("INSTALL_SHORTCUT intent could not be made safe — ignoring")
            return
        }
        @Suppress("DEPRECATION")
        val rawName = intent.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)

        val hostPackage = launch.`package` ?: launch.component?.packageName
        val name = rawName?.takeIf { it.isNotBlank() }
            ?: launch.component?.className?.substringAfterLast('.')
            ?: "Shortcut"
        val intentUri = launch.toUri(Intent.URI_INTENT_SCHEME)
        val hostLabel = hostPackage?.let { pkg ->
            runCatching {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(pkg, 0)
                ).toString()
            }.getOrNull()
        } ?: "Another app"

        Timber.i("INSTALL_SHORTCUT requested: name=$name host=$hostPackage — awaiting user confirmation")
        postConfirmation(context, name, intentUri, hostPackage, hostLabel)
    }

    // Posts an Add / Ignore notification. The add only happens via ShortcutConfirmReceiver, which is
    // not exported, so the confirmation can't be forged by the sending app.
    private fun postConfirmation(
        context: Context,
        name: String,
        intentUri: String,
        hostPackage: String?,
        hostLabel: String,
    ) {
        if (!canPostNotifications(context)) {
            Timber.w("Cannot prompt for shortcut \"$name\" — notifications not permitted; dropping")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Shortcut Requests", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Confirm shortcuts other apps want to add to your library" }
        )

        val notifId = intentUri.hashCode()
        fun pi(action: String, requestOffset: Int) = PendingIntent.getBroadcast(
            context,
            notifId + requestOffset,
            Intent(context, ShortcutConfirmReceiver::class.java).apply {
                this.action = action
                putExtra(ShortcutConfirmReceiver.EXTRA_NOTIF_ID, notifId)
                putExtra(ShortcutConfirmReceiver.EXTRA_NAME, name)
                putExtra(ShortcutConfirmReceiver.EXTRA_INTENT_URI, intentUri)
                putExtra(ShortcutConfirmReceiver.EXTRA_HOST_PACKAGE, hostPackage)
                putExtra(ShortcutConfirmReceiver.EXTRA_HOST_LABEL, hostLabel)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_input_add)
            .setContentTitle("Add \"$name\" to your library?")
            .setContentText("$hostLabel wants to add a game shortcut")
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Add", pi(ShortcutConfirmReceiver.ACTION_CONFIRM, 0)).build())
            .addAction(Notification.Action.Builder(null, "Ignore", pi(ShortcutConfirmReceiver.ACTION_DISMISS, 1)).build())
            .build()
        manager.notify(notifId, notification)
    }

    private fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_INSTALL_SHORTCUT = "com.android.launcher.action.INSTALL_SHORTCUT"
        private const val CHANNEL_ID = "pfp_shortcut_requests"
    }
}
