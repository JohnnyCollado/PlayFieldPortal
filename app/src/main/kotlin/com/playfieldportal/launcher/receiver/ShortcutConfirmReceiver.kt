package com.playfieldportal.launcher.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.core.domain.repository.GameRepository
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
 * Handles the user's decision on a captured shortcut (the Add / Ignore actions from
 * [InstallShortcutReceiver]'s notification). It is declared `exported="false"` and has no
 * intent-filter, so it can only be triggered by PFP's own PendingIntents — the sending app cannot
 * forge a confirmation. Only on [ACTION_CONFIRM] is the library entry actually created.
 */
class ShortcutConfirmReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun gameRepository(): GameRepository
        fun collectionRepository(): CollectionRepository
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)
        context.getSystemService(NotificationManager::class.java)?.cancel(notifId)

        if (intent.action != ACTION_CONFIRM) return // ACTION_DISMISS: notification already cancelled

        val name = intent.getStringExtra(EXTRA_NAME) ?: return
        val intentUri = intent.getStringExtra(EXTRA_INTENT_URI) ?: return
        val hostPackage = intent.getStringExtra(EXTRA_HOST_PACKAGE)
        val hostLabel = intent.getStringExtra(EXTRA_HOST_LABEL) ?: "Shortcuts"

        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        val gameRepository = deps.gameRepository()
        val collectionRepository = deps.collectionRepository()

        val pending = goAsync()
        scope.launch {
            try {
                val gameId = gameRepository.getByIntentUri(intentUri)?.id
                    ?: gameRepository.upsert(
                        Game(
                            title           = name,
                            platformId      = ANDROID_PLATFORM_ID,
                            packageName     = hostPackage,
                            isManualEntry   = true,
                            contentType     = GameContentType.SHORTCUT,
                            launchIntentUri = intentUri,
                        )
                    )
                val collectionId = collectionRepository.getAll().firstOrNull { it.name == hostLabel }?.id
                    ?: collectionRepository.create(hostLabel)
                collectionRepository.addGame(collectionId, gameId)
                Timber.i("User confirmed shortcut \"$name\" → collection \"$hostLabel\"")
            } catch (e: Exception) {
                Timber.e(e, "Failed to add confirmed shortcut \"$name\"")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CONFIRM = "com.playfieldportal.launcher.SHORTCUT_CONFIRM"
        const val ACTION_DISMISS = "com.playfieldportal.launcher.SHORTCUT_DISMISS"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val EXTRA_NAME = "name"
        const val EXTRA_INTENT_URI = "intent_uri"
        const val EXTRA_HOST_PACKAGE = "host_package"
        const val EXTRA_HOST_LABEL = "host_label"
        private const val ANDROID_PLATFORM_ID = "android"
    }
}
