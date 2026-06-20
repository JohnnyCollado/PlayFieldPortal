package com.playfieldportal.feature.appbar

import android.content.Context
import android.content.Intent
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Process
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val isGame: Boolean,
    val isEmulator: Boolean,
    val lastUsedAt: Long = 0L,
    // ApplicationInfo.category (CATEGORY_VIDEO, CATEGORY_AUDIO, …) or -1 when undefined.
    val systemCategory: Int = ApplicationInfo.CATEGORY_UNDEFINED,
)

// Known emulator package name prefixes — used to tag emulators in the app list
private val EMULATOR_PACKAGES = setOf(
    "com.retroarch",
    "com.retroarch.aarch64",
    "org.ppsspp.ppsspp",
    "org.ppsspp.ppsspg",
    "org.dolphinemu.dolphinemu",
    "com.duckstation",
    "com.nethersx2",
    "org.citra.citra_emu",
    "org.yuzu.yuzu_emu",
    "org.sudachi.sudachi_emu",
    "me.magnum.melonds",
    "com.mgba",
    "com.winlator",
    "com.winlator.plus",
    "com.limelight.noir",   // GameHub
    "com.gamenative",
)

@Singleton
class InstalledAppRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun getInstalledApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val lastUsedByPackage = loadLastUsedTimestamps()

        val launchIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolvedApps = pm.queryIntentActivities(launchIntent, PackageManager.GET_META_DATA)

        val apps = resolvedApps.mapNotNull { resolveInfo ->
            try {
                val appInfo = resolveInfo.activityInfo.applicationInfo
                val packageName = appInfo.packageName

                // Skip ourselves
                if (packageName == context.packageName) return@mapNotNull null

                val label = resolveInfo.loadLabel(pm).toString()
                val icon  = resolveInfo.loadIcon(pm)

                val isGame = appInfo.category == ApplicationInfo.CATEGORY_GAME ||
                             (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0

                val isEmulator = EMULATOR_PACKAGES.any { packageName.startsWith(it) }

                InstalledApp(
                    packageName    = packageName,
                    label          = label,
                    icon           = icon,
                    isGame         = isGame || isEmulator,
                    isEmulator     = isEmulator,
                    lastUsedAt     = lastUsedByPackage[packageName] ?: 0L,
                    systemCategory = appInfo.category,
                )
            } catch (e: Exception) {
                Timber.w("Failed to load app info: ${e.message}")
                null
            }
        }

        apps.sortedBy { it.label.lowercase() }
            .also { Timber.d("Installed apps loaded: ${it.size} total") }
    }

    fun launchApp(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            Timber.w("No launch intent for $packageName")
        }
    }

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccessSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            context.startActivity(intent)
        }.onFailure { e ->
            Timber.w(e, "Could not open usage access settings")
        }
    }

    private fun loadLastUsedTimestamps(): Map<String, Long> {
        if (!hasUsageAccess()) return emptyMap()

        val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
            ?: return emptyMap()
        val now = System.currentTimeMillis()
        val start = now - TimeUnit.DAYS.toMillis(30)

        return runCatching {
            usageStatsManager
                .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
                .orEmpty()
                .groupBy { it.packageName }
                .mapValues { (_, stats) -> stats.maxOf { it.lastTimeUsed } }
                .filterValues { it > 0L }
        }.getOrElse { e ->
            Timber.w(e, "Failed to load usage stats")
            emptyMap()
        }
    }
}
