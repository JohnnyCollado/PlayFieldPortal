package com.playfieldportal.feature.artwork.export

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.playfieldportal.core.data.database.dao.ArtworkImportReportDao
import com.playfieldportal.core.data.database.entity.ArtworkImportReportEntity
import com.playfieldportal.core.data.platform.PlatformFolderHintResolver
import com.playfieldportal.core.data.repository.ArtworkFolderRepository
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationDetail
import com.playfieldportal.core.domain.model.PfpErrorCode
import com.playfieldportal.core.domain.model.ResultItem
import com.playfieldportal.core.domain.model.ResultOutcome
import com.playfieldportal.core.domain.model.TaskKind
import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.feature.artwork.R
import com.playfieldportal.feature.artwork.importer.ImportSummary
import com.playfieldportal.feature.artwork.portable.ArtworkPathResolver
import com.playfieldportal.feature.artwork.portable.PortableArtworkLibrary
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import com.playfieldportal.core.ui.notification.BackgroundTaskCenter

/**
 * ES-DE-compatible export: copies the library's standard media directories into a user-picked
 * destination as `{esDeFolderName}/{mediaDir}/{file}` — point it at an ES-DE install's
 * `downloaded_media` folder and the artwork is immediately usable there.
 *
 * Rules: copy only (the live library is never touched or moved); missing-only (existing
 * destination files are skipped, so re-exports are incremental); `pfp/`, `import/` and the
 * manifest are never exported; kernel copies; cancellable; the outcome lands in the same
 * report list as imports.
 */
@HiltWorker
class ArtworkExportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val library: PortableArtworkLibrary,
    private val folderRepository: ArtworkFolderRepository,
    private val platformResolver: PlatformFolderHintResolver,
    private val reportDao: ArtworkImportReportDao,
    // The shared sink: the in-app notification panel (PFP's notifications are launcher-only).
    // Building a BackgroundTaskNotifier here reached the shade only, so this work ran and
    // finished without the panel ever hearing about it.
    private val tasks: BackgroundTaskCenter,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val destUriString = inputData.getString(KEY_DEST_TREE_URI)
            ?: return Result.failure(workDataOf(KEY_ERROR to applicationContext.getString(R.string.art_export_error_no_destination)))
        val destTree = Uri.parse(destUriString)
        val sourceTreeString = folderRepository.getTreeUri()
            ?: return Result.failure(workDataOf(KEY_ERROR to applicationContext.getString(R.string.art_no_folder_linked)))
        if (!folderRepository.hasLiveGrant()) {
            return Result.failure(workDataOf(KEY_ERROR to applicationContext.getString(R.string.art_export_error_access_lost)))
        }
        val sourceTree = Uri.parse(sourceTreeString)
        val startedAt = System.currentTimeMillis()

        var copied = 0
        var skipped = 0
        var failed = 0
        var bytes = 0L
        var processed = 0
        var cancelled = false
        // Each copy that failed, for the Results sheet. Successes are counted, not listed: an
        // export copies thousands of files and the user only needs the ones that did not make it.
        val failures = mutableListOf<ResultItem>()
        tasks.start(
            TASK_ID, applicationContext.getString(R.string.art_export_label), TaskKind.ARTWORK,
            onStop = { WorkManager.getInstance(applicationContext).cancelUniqueWork(UNIQUE_NAME) },
            stopNote = applicationContext.getString(R.string.art_export_stop_note),
        )

        try {
            // Artwork/{platform} children plus any legacy root-level platform dirs (v2 layout).
            for (platformDir in library.platformDirs(sourceTree)) {
                val destPlatformName = platformResolver.esDeFolderName(platformDir.name)
                for (mediaDir in library.listChildren(sourceTree, platformDir.documentId).filter { it.isDirectory }) {
                    // Standard media types only — pfp/ and unknown dirs stay private.
                    if (ArtworkPathResolver.kindForMediaDir(mediaDir.name) == null) continue
                    val files = library.listChildren(sourceTree, mediaDir.documentId)
                        .filter { !it.isDirectory && (it.sizeBytes ?: 0L) > 0L }
                    if (files.isEmpty()) continue
                    val destDirId = library.ensureDirPath(destTree, listOf(destPlatformName, mediaDir.name.lowercase(Locale.ROOT)))
                    if (destDirId == null) {
                        failed += files.size
                        failures += ResultItem(
                            primary = "$destPlatformName/${mediaDir.name.lowercase(Locale.ROOT)}",
                            outcome = ResultOutcome.FAILED,
                            badge = UiText.plural(R.plurals.art_export_files_badge, files.size).resolve(applicationContext),
                            reason = applicationContext.getString(R.string.art_export_dest_not_created),
                            code = PfpErrorCode.AR_2002.id,
                        )
                        continue
                    }
                    val existing = library.listChildren(destTree, destDirId)
                        .filterNot { it.isDirectory }
                        .map { it.name.lowercase(Locale.ROOT) }.toHashSet()
                    for (file in files) {
                        if (isStopped) throw CancellationException()
                        processed++
                        if (file.name.lowercase(Locale.ROOT) in existing) { skipped++; continue }
                        val mime = mimeFor(file.name)
                        if (library.copyDocument(file.uri, destTree, destDirId, file.name, mime)) {
                            copied++; bytes += file.sizeBytes ?: 0L
                        } else {
                            failed++
                            failures += ResultItem(
                                primary = file.name,
                                outcome = ResultOutcome.FAILED,
                                code = PfpErrorCode.AR_2002.id,
                                path = "$destPlatformName/${mediaDir.name.lowercase(Locale.ROOT)}",
                            )
                        }
                        if (processed % PROGRESS_STRIDE == 0) {
                            // No total up front: the export walks the tree as it copies, so
                            // the bar stays honestly indeterminate and the count rides setProgress.
                            setProgress(workDataOf(KEY_PROGRESS to processed))
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            cancelled = true
        } catch (e: Exception) {
            Timber.e(e, "Export failed")
            tasks.fail(
                TASK_ID, e.message ?: applicationContext.getString(R.string.art_error_unexpected), NotificationAction.OpenSettingsScreen("settings_artwork"),
                detail = NotificationDetail.notes(PfpErrorCode.AR_2002, summary = e.message,
                    diagnostic = e.stackTraceToString().take(4_000)),
                title = applicationContext.getString(R.string.art_export_failed_title),
            )
            persistReport(startedAt, copied, skipped, failed, bytes, cancelled = false)
            return Result.failure(workDataOf(KEY_ERROR to (e.message ?: applicationContext.getString(R.string.art_error_unexpected))))
        }

        persistReport(startedAt, copied, skipped, failed, bytes, cancelled)
        if (cancelled) {
            tasks.stopped(
                TASK_ID, UiText.plural(R.plurals.art_export_stopped_note, copied).resolve(applicationContext),
                detail = failures.takeIf { it.isNotEmpty() }?.let { NotificationDetail.results(it) },
                title = applicationContext.getString(R.string.art_export_stopped_title),
            )
            throw CancellationException("Export cancelled")
        }
        tasks.complete(
            TASK_ID,
            applicationContext.getString(R.string.art_export_result, copied, skipped, failed),
            NotificationAction.OpenSettingsScreen("settings_artwork"),
            detail = failures.takeIf { it.isNotEmpty() }?.let { NotificationDetail.results(it) },
            title = applicationContext.getString(R.string.art_export_finished_title),
        )
        return Result.success(workDataOf(KEY_COPIED to copied, KEY_FAILED to failed))
    }

    private suspend fun persistReport(startedAt: Long, copied: Int, skipped: Int, failed: Int, bytes: Long, cancelled: Boolean) {
        runCatching {
            reportDao.insert(
                ArtworkImportReportEntity(
                    source = "ES-DE Export",
                    startedAt = startedAt,
                    durationMs = System.currentTimeMillis() - startedAt,
                    summaryJson = ImportSummary.encode(
                        ImportSummary(
                            sourceLabel = "ES-DE Export",
                            transfer = "COPY",
                            imported = copied,
                            skipped = skipped,
                            failed = failed,
                            bytesWritten = bytes,
                            cancelled = cancelled,
                        )
                    ),
                )
            )
        }.onFailure { Timber.e(it, "Could not persist export report") }
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "mp4" -> "video/mp4"
        else -> "image/jpeg"
    }

    companion object {
        const val UNIQUE_NAME = "pfp_artwork_export"
        const val TASK_ID = "artwork_export"
        const val KEY_DEST_TREE_URI = "dest_tree_uri"
        const val KEY_ERROR = "error"
        const val KEY_COPIED = "copied"
        const val KEY_FAILED = "failed"
        const val KEY_PROGRESS = "processed"
        private const val PROGRESS_STRIDE = 25

        fun enqueue(context: Context, destTreeUri: Uri): UUID {
            val request = OneTimeWorkRequestBuilder<ArtworkExportWorker>()
                .setInputData(workDataOf(KEY_DEST_TREE_URI to destTreeUri.toString()))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
            return request.id
        }

        fun cancel(context: Context) =
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }
}
