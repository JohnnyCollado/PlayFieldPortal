package com.playfieldportal.launcher

import android.app.Application
import androidx.work.Configuration
import com.playfieldportal.core.data.database.seeder.DatabaseInitializer
import com.playfieldportal.core.data.database.seeder.StartupDataPrep
import com.playfieldportal.feature.artwork.api.ArtworkImageCache
import com.playfieldportal.feature.launcher.EmulatorAutoConfigService
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class PFPApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: androidx.hilt.work.HiltWorkerFactory
    @Inject lateinit var databaseInitializer: DatabaseInitializer
    @Inject lateinit var startupDataPrep: StartupDataPrep
    @Inject lateinit var emulatorProfileRepository: EmulatorProfileRepository
    @Inject lateinit var emulatorAutoConfigService: EmulatorAutoConfigService
    @Inject lateinit var artworkImageCache: ArtworkImageCache

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        initLogging()
        // Must run before anything can request an image: Coil builds its singleton loader on
        // first use and will not swap one out afterwards.
        artworkImageCache.installAsSingleton()
        initDatabase()
        initEmulators()
    }

    private fun initDatabase() {
        appScope.launch {
            runCatching {
                databaseInitializer.initialize()
                // Repair file-path drift from upgrades / restores before the UI reads the library.
                startupDataPrep.run(appVersionCode())
            }.onFailure { Timber.e(it, "Database initialization failed") }
        }
    }

    private fun appVersionCode(): Int = runCatching {
        // longVersionCode is available from API 28; minSdk is 29.
        packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
    }.getOrDefault(0)

    private fun initEmulators() {
        appScope.launch {
            runCatching {
                emulatorProfileRepository.initialize()
                emulatorAutoConfigService.runOnStartup()
            }.onFailure { Timber.e(it, "Emulator initialization failed") }
        }
    }

    private fun initLogging() {
        if (BuildConfig.DEBUG) {
            // Logcat gets the same redaction as the file log. A throwable's message can carry the
            // request URL (a ScreenScraper timeout printed both passwords), and Timber has already
            // appended the stack trace to the message by the time log() runs.
            Timber.plant(object : Timber.DebugTree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) =
                    super.log(priority, tag, com.playfieldportal.core.common.logging.LogRedaction.redact(message), t)
            })
        }
        // File log (Settings ▸ Logs) on every build: INFO+ only, redacted (credentials,
        // account names, emails never reach disk), size-capped — users can share these
        // files to report problems from the field.
        Timber.plant(
            com.playfieldportal.core.common.logging.PfpFileLoggingTree(
                java.io.File(filesDir, "logs")
            )
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(
                if (BuildConfig.DEBUG) android.util.Log.DEBUG
                else android.util.Log.WARN
            )
            .build()
}
