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
 * Runs Scan & Relink as real background work (C22 task T3).
 *
 * The Settings button used to launch the walk on `viewModelScope`, so navigating away killed it
 * mid-scan and the only feedback was a one-line notice at the end. A relink over a large library
 * is minutes of SAF traversal — it belongs here, with progress in the panel and the shade, exactly
 * like `MetadataScrapeWorker`.
 *
 * Two entry points share this worker:
 *  • the **Relink Artwork** item on the All Games memory card — a full-library walk;
 *  • the **automated** trigger after a library rescan — scoped to the platforms that gained games
 *    (see [ArtworkImportManager.RelinkScope]).
 *
 * `KEEP` is deliberate. Where the scrape worker's unique-work policy stops a second scrape from
 * doubling network traffic, here it stops an automated walk from interrupting a user-started one,
 * and the manager's own mutex is the second line of defence.
 */
@HiltWorker
class ArtworkRelinkWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val importManager: ArtworkImportManager,
    private val tasks: BackgroundTaskCenter,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val platformIds = inputData.getStringArray(KEY_PLATFORM_IDS)?.toSet().orEmpty()
        val scope =
            if (platformIds.isEmpty()) ArtworkImportManager.RelinkScope.FullLibrary
            else ArtworkImportManager.RelinkScope.Platforms(platformIds)
        val label =
            if (platformIds.isEmpty()) "Relinking artwork"
            else "Relinking artwork for ${platformIds.size} console(s)"
        tasks.start(TASK_ID, label, TaskKind.ARTWORK)
        var lastNotified = 0L

        return try {
            val result = importManager.relinkLibrary(scope = scope) { done, total, platform ->
                // Notifications are rate-limited by the system — refresh at most ~2x/second. The
                // final tick (done == total) always goes through so the bar never stalls short.
                val now = System.currentTimeMillis()
                if (now - lastNotified >= PROGRESS_INTERVAL_MS || done == total) {
                    lastNotified = now
                    tasks.progress(TASK_ID, done, total, platform)
                }
            }
            if (result == null) {
                // No folder linked, or the grant was lost. Not a failure the user can act on from
                // a notification, so it settles as a completed row that says what happened.
                tasks.complete(
                    TASK_ID,
                    "No artwork folder linked",
                    NotificationAction.OpenSettingsScreen(SETTINGS_ROUTE),
                )
                return Result.success()
            }
            tasks.complete(
                TASK_ID,
                buildString {
                    append("${result.gamesLinked} games linked of ${result.entriesScanned} files")
                    if (result.orphanEntries > 0) append(" · ${result.orphanEntries} unmatched")
                },
                NotificationAction.OpenSettingsScreen(
                    if (result.orphanEntries > 0) SETTINGS_ROUTE_ORPHANS else SETTINGS_ROUTE,
                ),
            )
            Result.success(
                workDataOf(
                    KEY_SCANNED to result.entriesScanned,
                    KEY_LINKED to result.gamesLinked,
                    KEY_ORPHANS to result.orphanEntries,
                )
            )
        } catch (e: CancellationException) {
            // The walk only ever adds and repoints records; whatever it linked before being
            // cancelled is already committed and correct.
            tasks.complete(TASK_ID, "Cancelled — artwork linked so far is kept")
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Artwork relink failed")
            tasks.fail(TASK_ID, e.message ?: "Unexpected error")
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unexpected error")))
        }
    }

    companion object {
        const val UNIQUE_NAME = "pfp_artwork_relink"
        const val TASK_ID = "artwork_relink"
        const val KEY_PLATFORM_IDS = "platform_ids"
        const val KEY_SCANNED = "scanned"
        const val KEY_LINKED = "linked"
        const val KEY_ORPHANS = "orphans"
        const val KEY_ERROR = "error"

        private const val PROGRESS_INTERVAL_MS = 500L
        private const val SETTINGS_ROUTE = "settings_artwork_import"
        private const val SETTINGS_ROUTE_ORPHANS = "settings_artwork_orphans"

        /**
         * Enqueues a relink. [platformIds] empty means the whole library.
         *
         * `KEEP`, so a relink already running is never restarted — including the automated one,
         * which fires far more often than a user presses anything.
         */
        fun enqueue(context: Context, platformIds: Set<String> = emptySet()): UUID {
            val request = OneTimeWorkRequestBuilder<ArtworkRelinkWorker>()
                .setInputData(workDataOf(KEY_PLATFORM_IDS to platformIds.toTypedArray()))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
            return request.id
        }

        fun cancel(context: Context) =
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }
}
