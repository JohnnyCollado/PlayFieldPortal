package com.playfieldportal.launcher.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.playfieldportal.core.common.security.ShortcutIntentSanitizer
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
 * Captures the legacy `com.android.launcher.action.INSTALL_SHORTCUT` broadcast.
 *
 * Apps like BannerHub and older Winlator builds still create game shortcuts by sending this
 * broadcast (instead of the modern ShortcutManager). Android 8+ no longer delivers it to manifest
 * receivers in the background, so PFP also registers an instance at runtime (see MainActivity)
 * while it's alive as the launcher. Each captured shortcut becomes a PFP entry that stores the
 * broadcast's launch Intent and is grouped into a collection named after the source app.
 *
 * Dependencies are pulled via a Hilt [EntryPoint] (rather than @AndroidEntryPoint) so the same
 * instance works whether registered in the manifest or at runtime.
 */
class InstallShortcutReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun gameRepository(): GameRepository
        fun collectionRepository(): CollectionRepository
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_SHORTCUT) return

        @Suppress("DEPRECATION")
        val rawLaunch = intent.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT) ?: run {
            Timber.w("INSTALL_SHORTCUT received with no EXTRA_SHORTCUT_INTENT — ignoring")
            return
        }
        // The broadcast is unauthenticated (any app can send it), so harden the supplied intent
        // before storing it: strip URI-permission grants and pin a real component. This blocks a
        // crafted shortcut from later coercing PFP into granting file access (confused deputy).
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
        } ?: "Shortcuts"

        Timber.i("Captured INSTALL_SHORTCUT: name=$name host=$hostPackage")

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
                Timber.i("Imported shortcut \"$name\" into collection \"$hostLabel\"")
            } catch (e: Exception) {
                Timber.e(e, "Failed to capture INSTALL_SHORTCUT for $name")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_SHORTCUT = "com.android.launcher.action.INSTALL_SHORTCUT"
        private const val ANDROID_PLATFORM_ID = "android"
    }
}
