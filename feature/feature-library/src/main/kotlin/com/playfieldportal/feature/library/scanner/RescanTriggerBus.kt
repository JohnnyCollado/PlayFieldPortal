package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.domain.artwork.ArtworkRelinkTrigger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import timber.log.Timber

/** Wall-clock seam so the resume-throttle boundary is drivable from tests (A3). */
fun interface RescanClock {
    fun now(): Long
}

@Singleton
class RescanTriggerBus @Inject constructor(
    private val libraryScanner: LibraryScanner,
    private val romRootDiscoveryScanner: RomRootDiscoveryScanner,
    private val scope: CoroutineScope,
    // Before [clock] on purpose: several tests pass the clock as a TRAILING LAMBDA, and a
    // fun-interface parameter added after it would silently capture that lambda instead, leaving
    // those tests on the real system clock. Defaults to no-op so the scan behaves exactly as it
    // did before artwork joined this path.
    private val artworkRelink: ArtworkRelinkTrigger = ArtworkRelinkTrigger.NoOp,
    private val clock: RescanClock = RescanClock { System.currentTimeMillis() },
) {
    private val scanMutex = Mutex()
    private var lastResumeRunAt = Long.MIN_VALUE
    private var mountJob: Job? = null

    fun submit(trigger: RescanTrigger) {
        when (trigger) {
            RescanTrigger.AppResumed -> {
                if (lastResumeRunAt != Long.MIN_VALUE &&
                    clock.now() - lastResumeRunAt < RESUME_THROTTLE_MS
                ) return
                scope.launch { scanNow("resume") }
            }
            RescanTrigger.MediaMounted, RescanTrigger.UsbDisconnected -> {
                mountJob?.cancel()
                mountJob = scope.launch {
                    delay(MOUNT_DEBOUNCE_MS)
                    scanNow("mount/unplug")
                }
            }
        }
    }

    private suspend fun scanNow(source: String) {
        if (!scanMutex.tryLock()) return
        try {
            if (source == "resume") lastResumeRunAt = clock.now()
            // Discovery first: a ROM dropped into a folder for a console with no Memory Card yet
            // (or a new subfolder under an existing root) is picked up here, so the incremental
            // scan right after sees the new card too. A failure here is non-fatal — the
            // incremental pass still runs against the cards that exist.
            runCatching { romRootDiscoveryScanner.discover() }
                .onFailure { Timber.w(it, "Library Rescan — console discovery failed ($source)") }
            val outcomes = libraryScanner.scanAllEnabled(removeMissing = true)
            Timber.i(
                "Library Rescan — done: ${outcomes.sumOf { it.added }} new, " +
                    "${outcomes.sumOf { it.markedMissing }} marked missing",
            )
            // Artwork for the games just added (task T5). Only the platforms that actually gained
            // something, and nothing at all when nothing was added — this runs on every resume, so
            // the common case must cost nothing. Fire-and-forget and non-fatal: artwork is never
            // allowed to fail a scan or make it wait.
            val gained = outcomes.filter { it.added > 0 }.map { it.platformId }.toSet()
            if (gained.isNotEmpty()) {
                runCatching { artworkRelink.relinkPlatforms(gained) }
                    .onFailure { Timber.w(it, "Library Rescan — artwork relink trigger failed ($source)") }
            }
        } catch (error: Throwable) {
            Timber.e(error, "Library Rescan — failed ($source)")
        } finally {
            scanMutex.unlock()
        }
    }

    companion object {
        const val RESUME_THROTTLE_MS = 5 * 60 * 1000L
        const val MOUNT_DEBOUNCE_MS = 2_000L
    }
}
