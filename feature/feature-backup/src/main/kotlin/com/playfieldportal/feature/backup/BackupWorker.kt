package com.playfieldportal.feature.backup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.playfieldportal.launcher.BuildConfig
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val backupManager: BackupManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        return when (
            val result = backupManager.createBackup(
                appVersionCode = BuildConfig.VERSION_CODE,
                appVersionName = BuildConfig.VERSION_NAME,
                createdAt = now,
            )
        ) {
            is BackupResult.Success -> Result.success(
                workDataOf(KEY_OUTPUT_PATH to result.file.absolutePath)
            )
            is BackupResult.Failure -> Result.failure(
                workDataOf(KEY_ERROR to result.reason)
            )
        }
    }

    companion object {
        const val TAG             = "pfp_backup"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_ERROR       = "error"
    }
}
