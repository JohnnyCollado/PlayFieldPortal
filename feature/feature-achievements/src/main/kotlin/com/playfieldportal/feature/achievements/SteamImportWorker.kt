package com.playfieldportal.feature.achievements

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.playfieldportal.core.ui.notification.BackgroundTaskNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.UUID

/**
 * Runs the Steam library import as real background work: a first import of a large library is a
 * 10-20 minute rate-limited walk, so it must survive leaving settings, show progress in the
 * shade, and be cancellable. Cancellation is cooperative — the importer suspends between games,
 * and everything synced so far is kept; the next run resumes from the playtime bookmarks.
 */
@HiltWorker
class SteamImportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val importer: SteamAccountImporter,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val notifier = BackgroundTaskNotifier(applicationContext)
        notifier.running(TASK_ID, "Importing Steam library", null)
        var lastNotified = 0L

        return try {
            val result = importer.import { done, total ->
                val now = System.currentTimeMillis()
                if (now - lastNotified >= 500 || done == total) {
                    lastNotified = now
                    notifier.running(
                        TASK_ID, "Importing Steam library — $done/$total",
                        if (total > 0) done.toFloat() / total else null,
                    )
                }
                setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
            }
            notifier.complete(TASK_ID, "Steam import finished", summaryOf(result))
            Result.success(
                workDataOf(
                    KEY_TOTAL to result.total,
                    KEY_IMPORTED to result.imported,
                    KEY_NO_COINS to result.noCoins,
                    KEY_NO_PROGRESS to result.noProgress,
                    KEY_FAILED to result.failed,
                    KEY_MISSING_CREDENTIALS to result.missingCredentials,
                    KEY_PROFILE_NOT_PUBLIC to result.profileNotPublic,
                )
            )
        } catch (e: CancellationException) {
            notifier.complete(TASK_ID, "Steam import cancelled", "Progress so far is kept — run again to resume")
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Steam import failed")
            notifier.failed(TASK_ID, "Steam import failed", "Run again to resume")
            Result.failure(workDataOf(KEY_ERROR to "import failed"))
        }
    }

    private fun summaryOf(r: SteamImportResult) = buildString {
        append("${r.imported} imported")
        if (r.noCoins > 0) append(", ${r.noCoins} without achievements")
        if (r.noProgress > 0) append(", ${r.noProgress} not played yet")
        if (r.failed > 0) append(", ${r.failed} failed")
    }

    companion object {
        const val UNIQUE_NAME = "pfp_steam_import"
        const val TASK_ID = "steam_import"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_IMPORTED = "imported"
        const val KEY_NO_COINS = "no_coins"
        const val KEY_NO_PROGRESS = "no_progress"
        const val KEY_FAILED = "failed"
        const val KEY_MISSING_CREDENTIALS = "missing_credentials"
        const val KEY_PROFILE_NOT_PUBLIC = "profile_not_public"
        const val KEY_ERROR = "error"

        /** Enqueues an import (no-op if one is already running — KEEP policy). */
        fun enqueue(context: Context): UUID {
            val request = OneTimeWorkRequestBuilder<SteamImportWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
            return request.id
        }

        fun cancel(context: Context) =
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }
}
