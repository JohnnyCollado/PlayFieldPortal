package com.playfieldportal.feature.appbar

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** One launcher shortcut published by a host app (e.g. a GameHub PC, a Winlator container). */
data class HarvestedShortcut(
    val hostPackage: String,
    val shortcutId: String,
    val label: String,
    val longLabel: String?,
)

sealed interface ShortcutHarvestResult {
    data class Success(val shortcuts: List<HarvestedShortcut>) : ShortcutHarvestResult
    // LauncherApps.getShortcuts() only works for the active Home app.
    object NotDefaultLauncher : ShortcutHarvestResult
    data class Error(val message: String) : ShortcutHarvestResult
}

/**
 * Reads per-game launcher shortcuts published by other apps via [LauncherApps], and launches them.
 *
 * This is the supported way to surface another app's individual items (GameHub/Moonlight PCs,
 * Lime3DS recent games, etc.) inside PFP. It requires PFP to be the **active default launcher** —
 * the OS rejects [LauncherApps.getShortcuts] from a non-home app with a SecurityException.
 */
@Singleton
class LauncherShortcutRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val launcherApps =
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

    fun isDefaultLauncher(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                return rm.isRoleHeld(RoleManager.ROLE_HOME)
            }
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName == context.packageName
    }.getOrDefault(false)

    suspend fun harvest(hostPackage: String): ShortcutHarvestResult = withContext(Dispatchers.IO) {
        if (!isDefaultLauncher()) return@withContext ShortcutHarvestResult.NotDefaultLauncher

        val query = LauncherApps.ShortcutQuery()
            .setPackage(hostPackage)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )

        runCatching {
            launcherApps.getShortcuts(query, Process.myUserHandle()).orEmpty()
                .filter { it.isEnabled }
                .map { info ->
                    HarvestedShortcut(
                        hostPackage = info.`package`,
                        shortcutId  = info.id,
                        label       = info.shortLabel?.toString()?.takeIf { it.isNotBlank() }
                            ?: info.longLabel?.toString()?.takeIf { it.isNotBlank() }
                            ?: info.id,
                        longLabel   = info.longLabel?.toString(),
                    )
                }
        }.fold(
            onSuccess = { ShortcutHarvestResult.Success(it) },
            onFailure = { e ->
                Timber.e(e, "Failed to harvest shortcuts for $hostPackage")
                if (e is SecurityException) ShortcutHarvestResult.NotDefaultLauncher
                else ShortcutHarvestResult.Error(e.message ?: "Couldn't read shortcuts")
            },
        )
    }

    fun launch(hostPackage: String, shortcutId: String): Result<Unit> = runCatching {
        launcherApps.startShortcut(hostPackage, shortcutId, null, null, Process.myUserHandle())
    }.onFailure { Timber.e(it, "startShortcut failed: $hostPackage / $shortcutId") }
}
