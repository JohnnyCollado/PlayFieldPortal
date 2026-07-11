package com.playfieldportal.feature.artwork.migrate

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.playfieldportal.core.data.database.dao.ArtworkImportReportDao
import com.playfieldportal.core.data.database.dao.ArtworkRecordDao
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.ArtworkImportReportEntity
import com.playfieldportal.core.data.repository.ArtworkFolderRepository
import com.playfieldportal.core.ui.notification.BackgroundTaskNotifier
import com.playfieldportal.feature.artwork.importer.ImportSummary
import com.playfieldportal.feature.artwork.store.ArtworkKind
import com.playfieldportal.feature.artwork.store.ArtworkTempIO
import com.playfieldportal.feature.artwork.store.InternalArtworkStore
import com.playfieldportal.feature.artwork.store.RoutingArtworkStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.UUID

/**
 * M-F2 — moves artwork stranded in internal storage (`filesDir/artwork/{gameId}/`, scraped or
 * picked before a folder was linked) into the user's portable library.
 *
 * Discipline, per the plan's §22/§16 rules:
 *  • Conflict gate first: an existing valid portable asset outranks the internal file — the
 *    redundant internal copy is deleted, nothing is written. A locked/user-assigned record
 *    whose file is dead is left alone (the internal file stays as its manual-recovery source).
 *  • Writes ride the exact scrape path ([RoutingArtworkStore.saveTempPortable]): payload
 *    sniffing + size caps, portable naming, record upsert, Coil cache-bust — versioned user
 *    picks migrate as `user_assigned + locked`, scraper files as `internal-migration`.
 *  • The internal file is deleted only AFTER a verified portable write; game columns that
 *    referenced it are repointed to the new content URI in the same step.
 *  • Resumable by construction: migrated slots hold valid portable records, so a re-run
 *    conflict-gates straight past them. Cancellation keeps everything migrated so far.
 */
@HiltWorker
class InternalArtworkMigrationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val internal: InternalArtworkStore,
    private val routing: RoutingArtworkStore,
    private val folderRepository: ArtworkFolderRepository,
    private val gameDao: GameDao,
    private val artworkRecordDao: ArtworkRecordDao,
    private val reportDao: ArtworkImportReportDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val notifier = BackgroundTaskNotifier(applicationContext)
        if (folderRepository.getTreeUri() == null || !folderRepository.hasLiveGrant()) {
            return Result.failure(workDataOf(KEY_ERROR to "No artwork folder linked"))
        }

        val assets = internal.enumerateForMigration()
        if (assets.isEmpty()) return Result.success(workDataOf(KEY_MIGRATED to 0))
        val games = gameDao.getAll().associateBy { it.id }
        val startedAt = System.currentTimeMillis()

        var migrated = 0
        var skipped = 0
        var failed = 0
        var bytes = 0L
        var cancelled = false
        notifier.running(TASK_ID, LABEL, null)

        try {
            assets.forEachIndexed { index, asset ->
                if (isStopped) throw CancellationException()
                val game = games[asset.gameId]
                if (game == null) { skipped++; return@forEachIndexed }   // orphan dir — untouched

                val record = artworkRecordDao.get(asset.gameId, asset.kind.name)
                val portableValid = record != null && routing.isValidRef(record.documentUri)
                when {
                    portableValid -> {
                        // Library already has this slot — the internal copy is redundant.
                        internal.deleteKind(asset.gameId, asset.kind)
                        skipped++
                    }
                    record != null && (record.locked || record.userAssigned) -> skipped++
                    else -> {
                        val sizeBytes = asset.sizeBytes
                        val tmp = runCatching {
                            asset.file.inputStream().use {
                                ArtworkTempIO.copyToTemp(it, applicationContext.cacheDir, asset.kind)
                            }
                        }.getOrNull()
                        val uri = tmp?.let {
                            routing.saveTempPortable(
                                gameId = asset.gameId,
                                kind = asset.kind,
                                tempFile = it,
                                source = if (asset.userPick) SOURCE_USER else SOURCE_MIGRATION,
                                userAssigned = asset.userPick,
                            )
                        }
                        if (uri == null) {
                            failed++
                        } else {
                            repointColumn(asset.gameId, asset.kind, asset.file.absolutePath, uri)
                            internal.deleteKind(asset.gameId, asset.kind)
                            migrated++
                            bytes += sizeBytes
                        }
                    }
                }

                if ((index + 1) % PROGRESS_STRIDE == 0 || index == assets.lastIndex) {
                    notifier.running(TASK_ID, LABEL, (index + 1).toFloat() / assets.size)
                    setProgressAsync(
                        workDataOf(KEY_PROGRESS_DONE to index + 1, KEY_PROGRESS_TOTAL to assets.size)
                    )
                }
            }
        } catch (e: CancellationException) {
            cancelled = true
        } catch (e: Exception) {
            Timber.e(e, "Internal artwork migration failed")
            notifier.failed(TASK_ID, "Artwork migration failed", e.message ?: "Unexpected error")
            persistReport(startedAt, migrated, skipped, failed, bytes, cancelled = false)
            return Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unexpected error")))
        }

        persistReport(startedAt, migrated, skipped, failed, bytes, cancelled)
        if (cancelled) {
            notifier.complete(TASK_ID, "Artwork migration cancelled", "$migrated files moved before cancelling")
            throw CancellationException("Migration cancelled")
        }
        notifier.complete(
            TASK_ID, "Artwork moved to your folder",
            "$migrated moved, $skipped already covered, $failed failed",
        )
        return Result.success(workDataOf(KEY_MIGRATED to migrated, KEY_FAILED to failed))
    }

    /** Repoints a column-backed kind at the new URI when it referenced the migrated file or is dead. */
    private suspend fun repointColumn(gameId: Long, kind: ArtworkKind, oldPath: String, uri: String) {
        val game = gameDao.getById(gameId) ?: return
        when (kind) {
            ArtworkKind.ICON ->
                if (game.iconUri == oldPath || !routing.isValidRef(game.iconUri)) gameDao.updateIconUri(gameId, uri)
            ArtworkKind.HERO ->
                if (game.heroUri == oldPath || !routing.isValidRef(game.heroUri)) gameDao.updateHero(gameId, uri)
            ArtworkKind.BACKGROUND ->
                if (game.artworkUri == oldPath || !routing.isValidRef(game.artworkUri)) gameDao.updateArtwork(gameId, uri)
            ArtworkKind.LOGO ->
                if (game.logoUri == oldPath || !routing.isValidRef(game.logoUri)) gameDao.updateLogo(gameId, uri)
            else -> Unit   // record-only kinds (manuals, videos, extras)
        }
    }

    private suspend fun persistReport(startedAt: Long, migrated: Int, skipped: Int, failed: Int, bytes: Long, cancelled: Boolean) {
        runCatching {
            reportDao.insert(
                ArtworkImportReportEntity(
                    source = REPORT_SOURCE,
                    startedAt = startedAt,
                    durationMs = System.currentTimeMillis() - startedAt,
                    summaryJson = ImportSummary.encode(
                        ImportSummary(
                            sourceLabel = REPORT_SOURCE,
                            transfer = "MOVE",
                            imported = migrated,
                            skipped = skipped,
                            failed = failed,
                            bytesWritten = bytes,
                            cancelled = cancelled,
                        )
                    ),
                )
            )
        }.onFailure { Timber.e(it, "Could not persist migration report") }
    }

    companion object {
        const val UNIQUE_NAME = "pfp_internal_artwork_migration"
        const val TASK_ID = "internal_artwork_migration"
        private const val LABEL = "Moving artwork into your folder"
        private const val REPORT_SOURCE = "Internal Migration"
        const val KEY_ERROR = "error"
        const val KEY_MIGRATED = "migrated"
        const val KEY_FAILED = "failed"
        const val KEY_PROGRESS_DONE = "done"
        const val KEY_PROGRESS_TOTAL = "total"
        private const val PROGRESS_STRIDE = 10
        private const val SOURCE_USER = "user"
        private const val SOURCE_MIGRATION = "internal-migration"

        fun enqueue(context: Context): UUID {
            val request = OneTimeWorkRequestBuilder<InternalArtworkMigrationWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
            return request.id
        }

        fun cancel(context: Context) =
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }
}
