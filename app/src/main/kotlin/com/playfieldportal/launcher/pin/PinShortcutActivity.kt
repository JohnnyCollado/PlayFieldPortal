package com.playfieldportal.launcher.pin

import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.playfieldportal.core.data.repository.CollectionRepository
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.launcher.ConsoleShortcutImporter
import com.playfieldportal.feature.launcher.PcShortcutImporter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Handles `android.content.pm.action.CONFIRM_PIN_SHORTCUT`. Declaring this activity is what makes
 * PFP advertise pin support (`isRequestPinShortcutSupported()` == true), so apps that create game
 * shortcuts via the modern `ShortcutManager.requestPinShortcut()` — the WinEmu "Add to home"
 * option, BannerHub, modern Winlator, etc. — succeed while PFP is the default launcher.
 *
 * Routing (docs/windows-library-refactor-plan.md section 3): a shortcut from a fingerprint-
 * verified PC launcher becomes a Windows Games card entry through [PcShortcutImporter] — written
 * immediately, with the setup notification raised when the library isn't configured yet. A
 * shortcut from a console emulator (X360 Mobile, XenDroid) lands on that console's platform card
 * through [ConsoleShortcutImporter]. Any other host keeps the collection behavior (an app-style
 * entry grouped under the source app).
 *
 * The activity is invisible; the store runs synchronously (bounded) before finish() so the write
 * cannot be lost to process death — the whole flow is one small DB transaction.
 */
@AndroidEntryPoint
class PinShortcutActivity : ComponentActivity() {

    @Inject lateinit var gameRepository: GameRepository
    @Inject lateinit var collectionRepository: CollectionRepository
    @Inject lateinit var pcShortcutImporter: PcShortcutImporter
    @Inject lateinit var consoleShortcutImporter: ConsoleShortcutImporter
    @Inject lateinit var shortcutRequestResolver: com.playfieldportal.feature.launcher.ShortcutRequestResolver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { handlePinRequest() }.onFailure { Timber.e(it, "Pin-shortcut handling failed") }
        finish()
    }

    private fun handlePinRequest() {
        val launcherApps = getSystemService(LauncherApps::class.java)
            ?: return Timber.w("Pin request: no LauncherApps service")
        val request = launcherApps.getPinItemRequest(intent)
            ?: return Timber.w("Pin request: intent carried no PinItemRequest")
        if (request.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) {
            return Timber.w("Pin request: unsupported type ${request.requestType}")
        }
        val shortcut = request.shortcutInfo
            ?: return Timber.w("Pin request: no shortcutInfo payload")

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
        // The host's publish stamp: what the reconcile sweep compares against so this pin is
        // imported once, not re-imported on every startup (PcShortcutLedger).
        val changedAt = shortcut.lastChangedTimestamp

        Timber.i("Accepted pinned shortcut: \"$label\" from $hostPackage")

        // Synchronous on purpose: finish() follows immediately and a detached write could be
        // lost with the process. The timeout keeps a wedged DB from ANRing the invisible pin UI.
        runBlocking {
            withTimeoutOrNull(STORE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    runCatching { store(hostPackage, shortcutId, label, changedAt, launcherApps, shortcut) }
                        .onFailure { Timber.e(it, "Failed to store pinned shortcut $shortcutId") }
                }
            } ?: Timber.e("Storing pinned shortcut $shortcutId timed out")
        }
    }

    private suspend fun store(
        hostPackage: String,
        shortcutId: String,
        label: String,
        changedAt: Long,
        launcherApps: LauncherApps,
        shortcut: ShortcutInfo,
    ) {
        if (pcShortcutImporter.isPcLauncher(hostPackage)) {
            val result = pcShortcutImporter.importPinnedShortcut(hostPackage, shortcutId, label, changedAt)
            if (result.needsSetup) shortcutRequestResolver.windowsSetupNeeded(label)
            return
        }
        if (consoleShortcutImporter.isConsoleHost(hostPackage)) {
            val result = consoleShortcutImporter.importPinnedShortcut(hostPackage, shortcutId, label)
            adoptShortcutIcon(result.gameId, launcherApps, shortcut)
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

    /**
     * The emulator draws its shortcut icon from the game's own title image, so a game with no art
     * of its own takes it as its box-art tile. A game that already has art keeps it.
     */
    private suspend fun adoptShortcutIcon(gameId: Long, launcherApps: LauncherApps, shortcut: ShortcutInfo) {
        val game = gameRepository.getById(gameId) ?: return
        if (game.artworkUri != null || game.boxArtUri != null || game.iconUri != null) return
        runCatching {
            val drawable = launcherApps.getShortcutIconDrawable(shortcut, resources.displayMetrics.densityDpi)
                ?: return
            val size = maxOf(drawable.intrinsicWidth, drawable.intrinsicHeight, 1).coerceAtMost(512)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            val dir = File(filesDir, "shortcut_icons").apply { mkdirs() }
            val file = File(dir, "$gameId.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            gameRepository.updateBoxArtTile(gameId, Uri.fromFile(file).toString())
        }.onFailure { Timber.w(it, "Couldn't keep the shortcut icon for game $gameId") }
    }

    private companion object {
        const val ANDROID_PLATFORM_ID = "android"
        const val STORE_TIMEOUT_MS = 5_000L
    }
}
