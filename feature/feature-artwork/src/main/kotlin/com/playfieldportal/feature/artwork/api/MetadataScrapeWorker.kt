package com.playfieldportal.feature.artwork.api

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.TaskKind
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.UUID

/**
 * Runs a metadata/artwork scrape batch as real background work: survives leaving the settings
 * screen, shows live progress in the notification shade, and is cancellable from the UI or the
 * notification. Cancellation is cooperative — the repository loop suspends between games and
 * network calls, so cancelling stops after the in-flight game and everything fetched so far
 * is kept.
 */
@HiltWorker
class MetadataScrapeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val artworkRepository: ArtworkRepository,
    // The shared sink: the in-app notification panel and the Android shade at once. Building a
    // BackgroundTaskNotifier here reached the shade only, so a scrape started from Settings ran
    // and finished without the panel ever hearing about it.
    private val tasks: BackgroundTaskCenter,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val mode = inputData.getString(KEY_MODE) ?: MODE_MISSING
        val label = if (mode == MODE_ALL) "Re-scraping all games" else "Scraping missing artwork"
        tasks.start(TASK_ID, label, TaskKind.ARTWORK)
        var lastNotified = 0L

        val onProgress: (ScrapeProgress) -> Unit = { p ->
            // Notifications are rate-limited by the system — refresh at most ~2×/second; the
            // in-app progress rides setProgress on every event.
            val now = System.currentTimeMillis()
            if (now - lastNotified >= 500 || p.current == p.total) {
                lastNotified = now
                // The counts go in whole now; the center derives the bar and the panel row
                // shows "14 / 56" and the title, instead of only a percentage.
                tasks.progress(TASK_ID, p.current, p.total, p.title)
            }
            setProgressAsync(
                workDataOf(
                    KEY_CURRENT to p.current,
                    KEY_TOTAL to p.total,
                    KEY_SUCCEEDED to p.succeeded,
                    KEY_FAILED to p.failed,
                    KEY_TITLE to p.title,
                    KEY_SOURCE to p.scrapeSource,
                    KEY_ASSET to p.scrapeAsset,
                )
            )
        }

        return try {
            val result = if (mode == MODE_ALL) artworkRepository.reScrapeAllGames(onProgress)
            else artworkRepository.scrapeMissingOnly(onProgress)
            tasks.complete(
                TASK_ID,
                "${result.succeeded} succeeded, ${result.failed} failed of ${result.total}",
                NotificationAction.OpenSettingsScreen("settings_artwork"),
            )
            Result.success(
                workDataOf(
                    KEY_SUCCEEDED to result.succeeded,
                    KEY_FAILED to result.failed,
                    KEY_TOTAL to result.total,
                    KEY_MODE to mode,
                )
            )
        } catch (e: CancellationException) {
            tasks.complete(TASK_ID, "Cancelled — artwork fetched so far is kept")
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Scrape batch failed")
            tasks.fail(TASK_ID, e.message ?: "Unexpected error")
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unexpected error")))
        }
    }

    companion object {
        const val UNIQUE_NAME = "pfp_metadata_scrape"
        const val TASK_ID = "metadata_scrape"
        const val MODE_ALL = "all"
        const val MODE_MISSING = "missing"
        const val KEY_MODE = "mode"
        const val KEY_CURRENT = "current"
        const val KEY_TOTAL = "total"
        const val KEY_SUCCEEDED = "succeeded"
        const val KEY_FAILED = "failed"
        const val KEY_TITLE = "title"
        const val KEY_SOURCE = "source"
        const val KEY_ASSET = "asset"
        const val KEY_ERROR = "error"

        /** Enqueues a scrape (no-op if one is already running — KEEP policy). */
        fun enqueue(context: Context, mode: String): UUID {
            val request = OneTimeWorkRequestBuilder<MetadataScrapeWorker>()
                .setInputData(workDataOf(KEY_MODE to mode))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
            return request.id
        }

        fun cancel(context: Context) =
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }
}
