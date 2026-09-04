package com.playfieldportal.feature.launcher

import android.os.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Coroutine scope the launch dispatcher runs its verification timers on (app-scoped). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LaunchDispatcherScope

/** Monotonic elapsed-realtime clock for launch-verification windows (injectable in tests). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LaunchRealtimeClock

@Module
@InstallIn(SingletonComponent::class)
object LaunchDispatcherModule {

    @Provides
    @Singleton
    @LaunchDispatcherScope
    fun provideLaunchDispatcherScope(): CoroutineScope =
        // Main.immediate: [LaunchDispatcher] mutates its pending state from MainActivity's
        // lifecycle callbacks (main thread) and from ViewModel launch sites (also main). Confining
        // the dispatcher's own jobs to the main thread makes those transitions race-free by
        // construction — no mutex needed for a tiny, low-frequency state machine.
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides
    @Singleton
    @LaunchRealtimeClock
    fun provideLaunchRealtimeClock(): LaunchClock = LaunchClock { SystemClock.elapsedRealtime() }
}
