package com.playfieldportal.launcher.pin

import android.content.pm.LauncherApps
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.launcher.PcShortcutImporter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * Handles `android.content.pm.action.CONFIRM_PIN_SHORTCUT`. Declaring this activity is what makes
 * PFP advertise pin support (`isRequestPinShortcutSupported()` == true), so apps that create game
 * shortcuts via the modern `ShortcutManager.requestPinShortcut()` — the WinEmu "Add to home"
 * option, BannerHub, modern Winlator, etc. — succeed while PFP is the default launcher.
 *
 * Routing (docs/windows-library-refactor-plan.md section 3): a shortcut from a fingerprint-
 * verified PC launcher becomes a Windows Games card entry through [PcShortcutImporter] — written
 * immediately, with the setup notification raised when the library isn't configured yet. Any
 * other host keeps the collection behavior (an app-style entry grouped under the source app).
 *
 * The activity is invisible; the store runs synchronously (bounded) before finish() so the write
 * cannot be lost to process death — the whole flow is one small DB transaction.
 */
@AndroidEntryPoint
class PinShortcutActivity : ComponentActivity() {

    @Inject lateinit var gameRepository: GameRepository
    @Inject lateinit var collectionRepository: CollectionRepository
    @Inject lateinit var pcShortcutImporter: PcShortcutImporter

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

        Timber.i("Accepted pinned shortcut: \"$label\" from $hostPackage")

        // Synchronous on purpose: finish() follows immediately and a detached write could be
        // lost with the process. The timeout keeps a wedged DB from ANRing the invisible pin UI.
        runBlocking {
            withTimeoutOrNull(STORE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    runCatching { store(hostPackage, shortcutId, label) }
                        .onFailure { Timber.e(it, "Failed to store pinned shortcut $shortcutId") }
                }
            } ?: Timber.e("Storing pinned shortcut $shortcutId timed out")
        }
    }

    private suspend fun store(hostPackage: String, shortcutId: String, label: String) {
        if (pcShortcutImporter.isPcLauncher(hostPackage)) {
            val result = pcShortcutImporter.importPinnedShortcut(hostPackage, shortcutId, label)
            if (result.needsSetup) WindowsSetupNotifications.post(applicationContext, label)
            return
        }

        // Non-PC host: an app-style entry grouped into a collection named after the source app.
        val hostLabel = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(hostPackage, 0)).toString()
        }.getOrNull() ?: "Shortcuts"
        val gameId = gameRepository.getLauncherShortcut(hostPackage, shortcutId)?.id
            ?: gameRepository.upsert(
                Game(
                    title         = label,
                    platformId    = ANDROID_PLATFORM_ID,
                    packageName   = hostPackage,
                    isManualEntry = true,
                    contentType   = GameContentType.ANDROID_APP,
                    shortcutId    = shortcutId,
                ),
            )
        val collectionId = collectionRepository.getAll().firstOrNull { it.name == hostLabel }?.id
            ?: collectionRepository.create(hostLabel)
        collectionRepository.addGame(collectionId, gameId)
        Timber.i("Stored pinned shortcut \"$label\" into collection \"$hostLabel\"")
    }

    private companion object {
        const val ANDROID_PLATFORM_ID = "android"
        const val STORE_TIMEOUT_MS = 5_000L
    }
}
