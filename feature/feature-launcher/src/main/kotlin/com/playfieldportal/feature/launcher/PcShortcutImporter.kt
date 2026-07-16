package com.playfieldportal.feature.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.data.repository.WindowsLibrarySetup
import com.playfieldportal.core.data.repository.WindowsSetupState
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.core.domain.repository.GameRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Links a freshly imported PC game to its achievement provider when the id is certain.
 * Declared here so the importer stays achievement-agnostic; feature-achievements binds the
 * STEAM implementation.
 */
interface PcGameAchievementLinker {
    suspend fun linkSteam(gameId: Long, appId: String)
}

/** Outcome of one shortcut import; [setup] tells the caller whether to raise the setup prompt. */
data class PcShortcutImportResult(
    val gameId: Long,
    val added: Boolean,
    val setup: WindowsSetupState,
) {
    val needsSetup: Boolean get() = setup !is WindowsSetupState.Ready
}

/**
 * The one importer behind every PC-shortcut funnel — modern pins and legacy INSTALL_SHORTCUT
 * captures produce identical Windows-card entries through it
 * (docs/windows-library-refactor-plan.md section 3). The entity is always written immediately
 * (pins are never lost); when library setup is incomplete the result says so and the setup
 * prompt is flagged for the next XMB open.
 *
 * Dedupe converges three arrival shapes on one game: the exact launch handle first
 * (shortcut id / intent uri), then the normalized title within the Windows card — a
 * folder-imported game and its shortcut merge, the shortcut attaching its launch handle to the
 * existing row.
 */
@Singleton
class PcShortcutImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gameRepository: GameRepository,
    private val memoryCards: MemoryCardRepository,
    private val windowsLibrary: WindowsLibrarySetup,
    private val achievementLinker: PcGameAchievementLinker,
) {
    /** The routing gate: true when [hostPackage] is a fingerprint-verified PC launcher. */
    fun isPcLauncher(hostPackage: String?): Boolean =
        PcLauncherCatalog.isVerifiedPcLauncher(hostPackage, context.packageManager)

    /**
     * Imports every shortcut currently pinned to PFP from verified PC launchers — the reconcile
     * sweep behind missed and UPDATED pins (re-pressing "Add to home" on an already-pinned game
     * only updates the shortcut; no confirm activity ever fires). Requires the default-launcher
     * role, like every pinned-shortcut read; returns the number of shortcuts imported.
     */
    suspend fun reconcilePinnedShortcuts(hostPackage: String? = null): Int {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            ?: return 0
        val hosts = hostPackage?.let { listOf(it) }
            ?: PcLauncherCatalog.entries.flatMap { it.packageNames }.distinct()
        // Pins recorded under ANOTHER launcher (a previous install, a different default) are
        // invisible to plain FLAG_MATCH_PINNED — the API-30+ any-launcher flag recovers them.
        val pinFlags = LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
            (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED_BY_ANY_LAUNCHER else 0)
        var imported = 0
        for (host in hosts.filter { isPcLauncher(it) }) {
            val query = LauncherApps.ShortcutQuery()
                .setPackage(host)
                .setQueryFlags(pinFlags)
            val pinned = runCatching { launcherApps.getShortcuts(query, Process.myUserHandle()) }
                .onFailure { Timber.w(it, "Pinned-shortcut query failed for $host (not the Home app?)") }
                .getOrNull().orEmpty()
            for (shortcut in pinned.filter { it.isEnabled }) {
                importPinnedShortcut(
                    hostPackage = host,
                    shortcutId  = shortcut.id,
                    label       = shortcut.shortLabel?.toString()?.takeIf { it.isNotBlank() }
                        ?: shortcut.longLabel?.toString()?.takeIf { it.isNotBlank() }
                        ?: shortcut.id,
                )
                imported++
            }
        }
        if (imported > 0) Timber.i("Pin reconcile — $imported shortcut(s) imported")
        return imported
    }

    /**
     * Watches the OS for shortcut changes from verified PC launchers and reconciles them live —
     * how an UPDATED pin (no confirm fires) still lands in the library the moment the emulator
     * publishes it. Safe to call once per app session; events arrive only while PFP holds the
     * Home role.
     */
    fun watchPinChanges(scope: CoroutineScope) {
        if (watcherRegistered) return
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            ?: return
        // Startup sweep: recover pins that changed while PFP wasn't running (or that belong to
        // another install's launcher record) without waiting for a manual scan.
        scope.launch {
            runCatching { reconcilePinnedShortcuts() }
                .onFailure { Timber.e(it, "Startup pin reconcile failed") }
        }
        val callback = object : LauncherApps.Callback() {
            override fun onShortcutsChanged(
                packageName: String,
                shortcuts: MutableList<android.content.pm.ShortcutInfo>,
                user: android.os.UserHandle,
            ) {
                if (!isPcLauncher(packageName)) return
                scope.launch {
                    runCatching { reconcilePinnedShortcuts(packageName) }
                        .onFailure { Timber.e(it, "Pin reconcile failed for $packageName") }
                }
            }

            override fun onPackageRemoved(packageName: String?, user: android.os.UserHandle?) = Unit
            override fun onPackageAdded(packageName: String?, user: android.os.UserHandle?) = Unit
            override fun onPackageChanged(packageName: String?, user: android.os.UserHandle?) = Unit
            override fun onPackagesAvailable(p: Array<out String>?, u: android.os.UserHandle?, r: Boolean) = Unit
            override fun onPackagesUnavailable(p: Array<out String>?, u: android.os.UserHandle?, r: Boolean) = Unit
        }
        runCatching {
            launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
            watcherRegistered = true
        }.onFailure { Timber.e(it, "Could not register the pin-change watcher") }
    }

    @Volatile
    private var watcherRegistered = false

    /** Imports a modern pinned/published shortcut, launched via `startShortcut(package, id)`. */
    suspend fun importPinnedShortcut(
        hostPackage: String,
        shortcutId: String,
        label: String,
    ): PcShortcutImportResult {
        val existing = gameRepository.getLauncherShortcut(hostPackage, shortcutId)
            ?: titleMatch(label)?.let { match ->
                // The shortcut is the launch handle the folder-imported row was missing.
                if (match.shortcutId == null && match.launchIntentUri == null) {
                    gameRepository.upsert(match.copy(packageName = hostPackage, shortcutId = shortcutId))
                }
                match
            }
        val gameId = existing?.id ?: gameRepository.upsert(
            Game(
                title         = label,
                platformId    = WINDOWS_PLATFORM_ID,
                packageName   = hostPackage,
                shortcutId    = shortcutId,
                isManualEntry = true,
                contentType   = GameContentType.GAME,
            ),
        )
        gameNativeAppId(hostPackage, shortcutId)?.let { appId ->
            runCatching { achievementLinker.linkSteam(gameId, appId) }
                .onFailure { Timber.e(it, "STEAM link failed for appid $appId") }
        }
        return finish(gameId, added = existing == null, what = "pin \"$label\" from $hostPackage")
    }

    /** Imports a user-confirmed legacy INSTALL_SHORTCUT capture, launched via its intent uri. */
    suspend fun importLegacyShortcut(
        hostPackage: String,
        label: String,
        intentUri: String,
    ): PcShortcutImportResult {
        val existing = gameRepository.getByIntentUri(intentUri)
            ?: titleMatch(label)?.let { match ->
                if (match.shortcutId == null && match.launchIntentUri == null) {
                    gameRepository.upsert(match.copy(packageName = hostPackage, launchIntentUri = intentUri))
                }
                match
            }
        val gameId = existing?.id ?: gameRepository.upsert(
            Game(
                title           = label,
                platformId      = WINDOWS_PLATFORM_ID,
                packageName     = hostPackage,
                launchIntentUri = intentUri,
                isManualEntry   = true,
                contentType     = GameContentType.GAME,
            ),
        )
        steamAppIdFromIntentUri(intentUri)?.let { appId ->
            runCatching { achievementLinker.linkSteam(gameId, appId) }
                .onFailure { Timber.e(it, "STEAM link failed for appid $appId") }
        }
        return finish(gameId, added = existing == null, what = "legacy shortcut \"$label\" from $hostPackage")
    }

    private suspend fun finish(gameId: Long, added: Boolean, what: String): PcShortcutImportResult {
        val setup = runCatching { windowsLibrary.ensure() }
            .getOrElse { WindowsSetupState.FolderUnavailable }
        if (setup !is WindowsSetupState.Ready) windowsLibrary.flagSetupPrompt()
        runCatching { memoryCards.recountGames(WINDOWS_PLATFORM_ID) }
        Timber.i("PC shortcut import — $what (added=$added, setup=${setup::class.simpleName})")
        return PcShortcutImportResult(gameId, added, setup)
    }

    private suspend fun titleMatch(label: String): Game? {
        val key = normalizeTitle(label)
        return gameRepository.getByPlatform(WINDOWS_PLATFORM_ID)
            .firstOrNull { normalizeTitle(it.displayTitle) == key }
    }

    private companion object {
        const val WINDOWS_PLATFORM_ID = "windows"

        // GameNative encodes the store appid in its shortcut ids: game_<appid>.
        val GAME_NATIVE_ID = Regex("""game_(\d{1,12})""")

        fun gameNativeAppId(hostPackage: String, shortcutId: String): String? {
            if (PcLauncherCatalog.forPackage(hostPackage)?.type != PcLauncherType.GAMENATIVE) return null
            return GAME_NATIVE_ID.matchEntire(shortcutId)?.groupValues?.get(1)
        }

        // Only ids that are EXPLICITLY Steam appids are trusted: GameNative's app_id and the
        // GameHub family's steamAppId. GameHub's localGameId is its internal id — never a
        // provider link (docs/windows-library-refactor-plan.md section 1).
        fun steamAppIdFromIntentUri(intentUri: String): String? {
            val intent = runCatching { Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME) }
                .getOrNull() ?: return null
            val appId = intent.getIntExtra("app_id", -1).takeIf { it > 0 }?.toString()
                ?: intent.getStringExtra("steamAppId")?.trim()
            return appId?.takeIf { it.isNotEmpty() && it.length <= 12 && it.all(Char::isDigit) }
        }

        // Mirrors the Windows-card dedupe rule (normalizePcTitle / LocalSteamGameImporter).
        fun normalizeTitle(title: String): String =
            title.lowercase().filter { it.isLetterOrDigit() }
    }
}
