package com.playfieldportal.feature.appbar

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.HandlerThread
import android.os.UserHandle
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** Coroutine scope the app-list invalidation pipeline runs on (app-scoped). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppCatalogScope

@Module
@InstallIn(SingletonComponent::class)
object AppCatalogModule {

    @Provides
    @Singleton
    @AppCatalogScope
    fun provideAppCatalogScope(): CoroutineScope =
        // IO: every reaction to a package event is a PackageManager sweep, never UI work.
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/**
 * Tells the rest of the app that the set of installed packages moved.
 *
 * Everything that lists apps — the App Picker, the XMB's app categories, App Visibility, the App
 * Drawer — derives from a PackageManager enumeration, and nothing used to invalidate it. That was
 * survivable while PFP was an ordinary app, because the process died often enough to rebuild the
 * list by accident. As the home app the process is effectively immortal, so "once at first load"
 * became "once, ever": a sideloaded APK never appeared until a reboot.
 *
 * [LauncherApps.Callback] rather than a PACKAGE_ADDED/REMOVED receiver, for two reasons. An APK
 * *update* arrives as [LauncherApps.Callback.onPackageChanged] and not as an add, so an add/remove
 * receiver silently misses every reinstall — which on a sideloading handheld is most installs. And
 * the available/unavailable pair covers packages coming and going with external storage, which the
 * package broadcasts do not.
 *
 * Emissions carry nothing. "The app list moved" is the whole fact; every consumer re-reads.
 */
@Singleton
class InstalledPackageMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Conflated on purpose. One install is several callbacks a few hundred ms apart, and a
    // consumer that sweeps PackageManager per callback would sweep three times for one event.
    // Subscribers debounce on top of this; the buffer only keeps a burst from being dropped
    // before the debounce window sees it.
    private val _packageChanges = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val packageChanges: SharedFlow<Unit> = _packageChanges.asSharedFlow()

    // LauncherApps delivers on the handler's thread. A dedicated one keeps package churn off the
    // main looper, where it would land during exactly the install/uninstall moments the user is
    // watching an animation.
    private val handler by lazy {
        Handler(HandlerThread("pfp-package-monitor").apply { start() }.looper)
    }

    @Volatile private var started: Boolean = false

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = signal()
        override fun onPackageRemoved(packageName: String, user: UserHandle) = signal()
        override fun onPackageChanged(packageName: String, user: UserHandle) = signal()
        override fun onPackagesAvailable(
            packageNames: Array<out String>?,
            user: UserHandle?,
            replacing: Boolean,
        ) = signal()
        override fun onPackagesUnavailable(
            packageNames: Array<out String>?,
            user: UserHandle?,
            replacing: Boolean,
        ) = signal()
    }

    /**
     * Registers for package events. Idempotent, and safe to call before anything consumes
     * [packageChanges] — the flow simply has no subscribers yet.
     *
     * Called explicitly from PFPApplication rather than from this class's constructor: Hilt builds
     * singletons lazily, so registering as a construction side effect would make it depend on
     * whoever happens to inject the monitor first.
     */
    fun start() {
        if (started) return
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        if (launcherApps == null) {
            Timber.w("No LauncherApps service — installed-app lists will not self-refresh")
            return
        }
        runCatching { launcherApps.registerCallback(callback, handler) }
            .onSuccess { started = true; Timber.i("Package monitor registered") }
            .onFailure { Timber.w(it, "Could not register the package monitor") }
    }

    // Verbose, not info: this fires for component enable/disable too, so at info it would spam the
    // on-device file log. `delivered` is false when nothing is subscribed yet, which is the first
    // thing to check if a package event never reaches the catalog.
    private fun signal() {
        val delivered = _packageChanges.tryEmit(Unit)
        Timber.v("Package event (delivered=$delivered)")
    }
}
