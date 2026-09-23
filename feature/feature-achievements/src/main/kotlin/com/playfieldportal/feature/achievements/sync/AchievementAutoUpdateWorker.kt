package com.playfieldportal.feature.achievements.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The scheduled achievement check: one [SyncTrigger.AUTOMATIC] update of present, matched games.
 * Quiet by design — the reporter leaves a tray row only when something changed or needs action.
 */
@HiltWorker
class AchievementAutoUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: AchievementSyncCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val summary = coordinator.updateInstalled(SyncTrigger.AUTOMATIC)
        Timber.i("Scheduled achievement check: %s", summary)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Scheduled achievement check failed")
        Result.failure()
    }
}

/**
 * Enqueues the scheduled check when PFP is used (startup, return to the foreground) — never a
 * periodic wake-up of its own. At most one unique, network- and battery-constrained job, and only
 * when some provider is past its 24-hour window and updates are not paused after a clear.
 */
@Singleton
class AchievementAutoUpdateScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coordinator: AchievementSyncCoordinator,
) {
    suspend fun onAppUsed() {
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        // The retired account-wide Steam importer's unique work: never let an old queued run
        // resurface (its worker class no longer exists).
        workManager.cancelUniqueWork(LEGACY_STEAM_IMPORT)
        if (!coordinator.isAutomaticCheckDue()) return
        val request = OneTimeWorkRequestBuilder<AchievementAutoUpdateWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val UNIQUE_NAME = "pfp_achievement_auto_update"
        const val LEGACY_STEAM_IMPORT = "pfp_steam_import"
    }
}
