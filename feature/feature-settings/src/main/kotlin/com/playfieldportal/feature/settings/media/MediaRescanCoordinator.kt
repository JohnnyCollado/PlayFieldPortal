package com.playfieldportal.feature.settings.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Strategic automatic scan triggers for the user-managed media roots.
 *
 * Media is intentionally not watched continuously: SAF trees have no reliable portable filesystem
 * watcher, and polling would waste battery. A throttled foreground pass catches files added while
 * the launcher was away; a debounced mount pass catches SD/USB media becoming available.
 */
@Singleton
class MediaRescanCoordinator @Inject constructor(
    private val runner: WizardMediaScanRunner,
) {
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var mountJob: Job? = null
    @Volatile private var lastResumeRunAt = Long.MIN_VALUE

    fun onResume(now: Long = System.currentTimeMillis()) {
        if (lastResumeRunAt != Long.MIN_VALUE && now - lastResumeRunAt < RESUME_THROTTLE_MS) return
        lastResumeRunAt = now
        kickoffAll("resume")
    }

    fun onMediaMounted() {
        mountJob?.cancel()
        mountJob = scope.launch {
            delay(MOUNT_DEBOUNCE_MS)
            kickoffAll("mount")
        }
    }

    private fun kickoffAll(source: String) {
        Timber.i("Media rescan trigger: %s", source)
        runner.kickoffAll()
    }

    companion object {
        const val RESUME_THROTTLE_MS = 5 * 60 * 1000L
        const val MOUNT_DEBOUNCE_MS = 2_000L
    }
}
