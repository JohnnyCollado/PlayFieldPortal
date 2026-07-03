package com.playfieldportal.feature.backup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
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
        // Version info passed in by the caller (app module owns BuildConfig)
        val versionCode = inputData.getInt(KEY_INPUT_VERSION_CODE, 0)
        val versionName = inputData.getString(KEY_INPUT_VERSION_NAME) ?: "unknown"
        return when (
            val result = backupManager.createBackup(
                appVersionCode = versionCode,
                appVersionName = versionName,
                createdAt      = now,
            )
        ) {
            is BackupResult.Success -> Result.success(
                workDataOf(KEY_OUTPUT_PATH to result.displayName)
            )
            is BackupResult.Failure -> Result.failure(
                workDataOf(KEY_ERROR to result.reason)
            )
        }
    }

    companion object {
        const val TAG                    = "pfp_backup"
        const val KEY_INPUT_VERSION_CODE = "version_code"
        const val KEY_INPUT_VERSION_NAME = "version_name"
        const val KEY_OUTPUT_PATH        = "output_path"
        const val KEY_ERROR              = "error"
    }
}
