package com.playfieldportal.launcher

import android.app.Application
import androidx.work.Configuration
import com.playfieldportal.core.data.database.seeder.DatabaseInitializer
import com.playfieldportal.core.data.database.seeder.StartupDataPrep
import com.playfieldportal.feature.appbar.InstalledAppReconciler
import com.playfieldportal.feature.appbar.InstalledPackageMonitor
import com.playfieldportal.feature.artwork.api.ArtworkImageCache
import com.playfieldportal.feature.artwork.api.ArtworkImportManager
import com.playfieldportal.feature.launcher.kb.EmulatorKbUpdater
import com.playfieldportal.feature.launcher.kb.appVersionCode
import com.playfieldportal.feature.launcher.kb.EmulatorKnowledgeRefresher
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    @Inject lateinit var emulatorKnowledgeRefresher: EmulatorKnowledgeRefresher
    @Inject lateinit var emulatorKbUpdater: EmulatorKbUpdater
    @Inject lateinit var artworkImageCache: ArtworkImageCache
    @Inject lateinit var installedPackageMonitor: InstalledPackageMonitor
    @Inject lateinit var installedAppReconciler: InstalledAppReconciler
    @Inject lateinit var artworkImportManager: ArtworkImportManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        initLogging()
        // Must run before anything can request an image: Coil builds its singleton loader on
        // first use and will not swap one out afterwards.
        artworkImageCache.installAsSingleton()
        val databaseReady = initDatabase()
        initEmulators(databaseReady)
        // Registered here, not lazily on first injection: as the home app this process outlives
        // every screen, so the app catalog has to be invalidated by package events rather than by
        // the process dying. Cheap — one callback registration.
        installedPackageMonitor.start()
        // Subscribes to the catalog the monitor feeds, so app-backed library rows follow what is
        // actually installed. Started here for the same reason: a lazily-built singleton nobody
        // injects never runs.
        installedAppReconciler.start()
        initArtworkLibrary()
    }

    // The library "opens" with the process: mark its artwork folders .nomedia, including ones no
    // write has passed through since the marker was introduced.
    private fun initArtworkLibrary() {
        appScope.launch {
            runCatching { artworkImportManager.markLibraryFolders() }
                .onFailure { Timber.w(it, "Marking artwork folders .nomedia failed") }
        }
    }

    private fun initDatabase(): Job =
        appScope.launch {
            runCatching {
                databaseInitializer.initialize()
                // Repair file-path drift from upgrades / restores before the UI reads the library.
                startupDataPrep.run(appVersionCode())
            }.onFailure { Timber.e(it, "Database initialization failed") }
        }

    // Waits for the database seed: the refresher rewrites stored game, card and platform references.
    private fun initEmulators(databaseReady: Job) {
        appScope.launch {
            databaseReady.join()
            try {
                emulatorProfileRepository.initialize()
                emulatorKnowledgeRefresher.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Emulator initialization failed")
            }
            try {
                // Toggle and 24 h throttle are the updater's own; a no-op when not due.
                emulatorKbUpdater.check(manual = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Emulator knowledge update check failed")
            }
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
