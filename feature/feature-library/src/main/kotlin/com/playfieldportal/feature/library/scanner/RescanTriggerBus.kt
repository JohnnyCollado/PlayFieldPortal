package com.playfieldportal.feature.library.scanner

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
    private val scope: CoroutineScope,
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
            val outcomes = libraryScanner.scanAllEnabled(removeMissing = true)
            Timber.i(
                "Library Rescan — done: ${outcomes.sumOf { it.added }} new, " +
                    "${outcomes.sumOf { it.markedMissing }} marked missing",
            )
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
