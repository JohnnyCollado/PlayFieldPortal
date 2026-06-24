package com.playfieldportal.launcher.pin

import android.content.pm.LauncherApps
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.core.domain.repository.GameRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Handles `android.content.pm.action.CONFIRM_PIN_SHORTCUT`. Declaring this activity is what makes
 * PFP advertise pin support (`isRequestPinShortcutSupported()` == true), so apps that create game
 * shortcuts via the modern `ShortcutManager.requestPinShortcut()` — BannerHub, modern Winlator,
 * etc. — succeed while PFP is the default launcher.
 *
 * It accepts the pin and records the shortcut as a launchable PFP entry (host package + shortcut
 * id, launched via LauncherApps.startShortcut), grouped into a collection named after the source
 * app. The activity is invisible and finishes immediately.
 */
@AndroidEntryPoint
class PinShortcutActivity : ComponentActivity() {

    @Inject lateinit var gameRepository: GameRepository
    @Inject lateinit var collectionRepository: CollectionRepository

    // Detached so the DB write survives finish().
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { handlePinRequest() }.onFailure { Timber.e(it, "Pin-shortcut handling failed") }
        finish()
    }

    private fun handlePinRequest() {
        val launcherApps = getSystemService(LauncherApps::class.java) ?: return
        val request = launcherApps.getPinItemRequest(intent) ?: return
        if (request.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) return
        val shortcut = request.shortcutInfo ?: return

        // Accept first so the shortcut is pinned to PFP regardless of what happens next.
        if (!runCatching { request.accept() }.getOrDefault(false)) {
            Timber.w("Pin request not accepted for ${shortcut.id}")
            return
        }

        val hostPackage = shortcut.`package`
        val shortcutId = shortcut.id
        val label = shortcut.shortLabel?.toString()?.takeIf { it.isNotBlank() }
            ?: shortcut.longLabel?.toString()?.takeIf { it.isNotBlank() }
            ?: shortcutId
        val hostLabel = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(hostPackage, 0)).toString()
        }.getOrNull() ?: "Shortcuts"

        Timber.i("Accepted pinned shortcut: \"$label\" from $hostPackage")

        val games = gameRepository
        val collections = collectionRepository
        ioScope.launch {
            runCatching {
                val gameId = games.getLauncherShortcut(hostPackage, shortcutId)?.id
                    ?: games.upsert(
                        Game(
                            title         = label,
                            platformId    = ANDROID_PLATFORM_ID,
                            packageName   = hostPackage,
                            isManualEntry = true,
                            contentType   = GameContentType.ANDROID_APP,
                            shortcutId    = shortcutId,
                        )
                    )
                val collectionId = collections.getAll().firstOrNull { it.name == hostLabel }?.id
                    ?: collections.create(hostLabel)
                collections.addGame(collectionId, gameId)
                Timber.i("Stored pinned shortcut \"$label\" into collection \"$hostLabel\"")
            }.onFailure { Timber.e(it, "Failed to store pinned shortcut $shortcutId") }
        }
    }

    private companion object {
        const val ANDROID_PLATFORM_ID = "android"
    }
}
