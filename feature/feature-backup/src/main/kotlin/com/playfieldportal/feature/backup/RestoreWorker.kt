package com.playfieldportal.feature.backup

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class RestoreWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val backupManager: BackupManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_URI)
            ?: return Result.failure(workDataOf(KEY_ERROR to "No URI provided"))

        val uri = Uri.parse(uriString)

        return when (val result = backupManager.restoreBackup(uri)) {
            // A restore can succeed having turned something away — an entry outside the folders a
            // backup owns, or an emulator profile that failed admission. Carrying the list out
            // means the user is told rather than left with silently missing data.
            is RestoreResult.Success -> Result.success(
                workDataOf(KEY_REFUSALS to result.refusals.toTypedArray())
            )
            is RestoreResult.Failure -> Result.failure(
                workDataOf(KEY_ERROR to result.reason)
            )
        }
    }

    companion object {
        const val TAG      = "pfp_restore"
        const val KEY_URI  = "backup_uri"
        const val KEY_ERROR = "error"
        const val KEY_REFUSALS = "refusals"
    }
}
