package com.playfieldportal.launcher

import android.app.Application
import androidx.work.Configuration
import com.playfieldportal.core.data.database.seeder.DatabaseInitializer
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
    @Inject lateinit var emulatorProfileRepository: EmulatorProfileRepository
    @Inject lateinit var emulatorAutoConfigService: EmulatorAutoConfigService

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        initLogging()
        initDatabase()
        initEmulators()
    }

    private fun initDatabase() {
        appScope.launch {
            runCatching { databaseInitializer.initialize() }
                .onFailure { Timber.e(it, "Database initialization failed") }
        }
    }

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
            Timber.plant(Timber.DebugTree())
        }
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
