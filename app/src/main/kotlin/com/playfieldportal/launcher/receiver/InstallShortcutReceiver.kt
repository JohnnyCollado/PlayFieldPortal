package com.playfieldportal.launcher.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.playfieldportal.core.common.security.ShortcutIntentSanitizer
import com.playfieldportal.core.data.repository.PendingShortcutRequest
import com.playfieldportal.feature.launcher.ShortcutRequestResolver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Captures the legacy `com.android.launcher.action.INSTALL_SHORTCUT` broadcast.
 *
 * Apps like BannerHub and older Winlator builds still create game shortcuts by sending this
 * broadcast. The broadcast is unauthenticated — any app can send it — so PFP does NOT add the
 * shortcut silently. It hardens the supplied intent ([ShortcutIntentSanitizer]) and queues the
 * request; the launcher then asks Add / Ignore in its own modal (with an unread tray row as the way
 * back to it). Only the user's choice there creates the library entry, which prevents both
 * confused-deputy abuse and silent library poisoning. This used to be Add / Ignore buttons on an
 * Android shade notification; PFP's notifications are launcher-only now.
 */
class InstallShortcutReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun shortcutRequestResolver(): ShortcutRequestResolver
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        val request = PendingShortcutRequest(
            id = Integer.toHexString(intentUri.hashCode()),
            name = name,
            intentUri = intentUri,
            hostPackage = hostPackage,
            hostLabel = hostLabel,
            requestedAt = System.currentTimeMillis(),
        )
        val resolver = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
            .shortcutRequestResolver()
        val pending = goAsync()
        scope.launch {
            try {
                resolver.enqueue(request)
            } catch (e: Exception) {
                Timber.e(e, "Could not queue shortcut request \"$name\"")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_SHORTCUT = "com.android.launcher.action.INSTALL_SHORTCUT"
    }
}
