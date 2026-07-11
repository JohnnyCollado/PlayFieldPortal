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
import com.playfieldportal.core.ui.notification.BackgroundTaskNotifier
import com.playfieldportal.feature.artwork.importer.ImportSummary
import com.playfieldportal.feature.artwork.portable.ArtworkPathResolver
import com.playfieldportal.feature.artwork.portable.PortableArtworkLibrary
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.Locale
import java.util.UUID

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
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val notifier = BackgroundTaskNotifier(applicationContext)
        val destUriString = inputData.getString(KEY_DEST_TREE_URI)
            ?: return Result.failure(workDataOf(KEY_ERROR to "No destination folder"))
        val destTree = Uri.parse(destUriString)
        val sourceTreeString = folderRepository.getTreeUri()
            ?: return Result.failure(workDataOf(KEY_ERROR to "No artwork folder linked"))
        if (!folderRepository.hasLiveGrant()) {
            return Result.failure(workDataOf(KEY_ERROR to "Artwork folder access was lost"))
        }
        val sourceTree = Uri.parse(sourceTreeString)
        val startedAt = System.currentTimeMillis()

        var copied = 0
        var skipped = 0
        var failed = 0
        var bytes = 0L
        var processed = 0
        var cancelled = false
        notifier.running(TASK_ID, LABEL, null)

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
                    if (destDirId == null) { failed += files.size; continue }
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
                        } else failed++
                        if (processed % PROGRESS_STRIDE == 0) {
                            notifier.running(TASK_ID, LABEL, null)
                            setProgress(workDataOf(KEY_PROGRESS to processed))
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            cancelled = true
        } catch (e: Exception) {
            Timber.e(e, "Export failed")
            notifier.failed(TASK_ID, "Artwork export failed", e.message ?: "Unexpected error")
            persistReport(startedAt, copied, skipped, failed, bytes, cancelled = false)
            return Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unexpected error")))
        }

        persistReport(startedAt, copied, skipped, failed, bytes, cancelled)
        if (cancelled) {
            notifier.complete(TASK_ID, "Artwork export cancelled", "$copied files exported before cancelling")
            throw CancellationException("Export cancelled")
        }
        notifier.complete(TASK_ID, "Artwork export finished", "$copied copied, $skipped already present, $failed failed")
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
        private const val LABEL = "Exporting artwork for ES-DE"
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
