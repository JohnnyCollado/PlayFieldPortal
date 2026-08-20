package com.playfieldportal.feature.library.scanner

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import timber.log.Timber

/**
 * Decides WHEN a library-wide missing-ROM rescan is allowed to run, then runs it via
 * [LibraryScanner] — see docs/adr/0001-library-scanner-owns-rom-survey.md. This class owns only
 * the trigger guards so callers (MainActivity.onResume, a MEDIA_MOUNTED receiver) can call it
 * freely without worrying about spamming the SAF walk; the survey + reconcile policy itself lives
 * in [LibraryScanner], shared with the settings screen's manual scan.
 *
 * Three separate concerns, three separate guards — resist merging them, they fail differently:
 *  - Throttle (onResume only): a full folder walk on every "back out of a game" is wasteful and
 *    battery-costly, so onResume no-ops unless RESUME_THROTTLE_MS has passed since the last run.
 *  - Debounce (onMediaMounted only): a card mount fires a BURST of broadcasts (one per volume,
 *    sometimes duplicated by the OS). Debounce waits for MOUNT_DEBOUNCE_MS of quiet before
 *    actually scanning, so ten broadcasts in one second cause one scan, not ten.
 *  - Single-flight (both): a Mutex ensures only one rescan-all pass runs at a time here; per-card
 *    single-flight against a concurrent manual scan is enforced inside LibraryScanner itself.
 */
@Singleton
class LibraryRescanCoordinator @Inject constructor(
    private val libraryScanner: LibraryScanner,
) {
    private val scanMutex = Mutex()

    // Last time rescanAll actually ran (not just was requested). Guarded implicitly: only ever
    // written from inside the mutex-held section of rescanAll.
    private var lastRunAt = 0L

    // Incremented on every onMediaMounted() call. A pending call compares its own snapshot
    // against the live value after its delay — if another call bumped it in the meantime, this
    // one lost the race and bows out instead of double-scanning.
    private var mountToken = 0L

    /** Weak, frequent signal — throttled. */
    suspend fun onResume() {
        val now = System.currentTimeMillis()
        if (now - lastRunAt < RESUME_THROTTLE_MS) {
            Timber.d("Library Rescan — onResume skipped (throttled)")
            return
        }
        rescanAll()
    }

    /** Strong, rare signal — bypasses the throttle, but still debounced and single-flight. */
    suspend fun onMediaMounted() {
        val myToken = ++mountToken
        delay(MOUNT_DEBOUNCE_MS)
        if (myToken != mountToken) {
            Timber.d("Library Rescan — onMediaMounted superseded by a newer mount, skipping")
            return
        }
        rescanAll()
    }

    // tryLock, not withLock: if a scan is already running, this call skips rather than queues.
    // Queuing would mean a resume that arrives mid-scan waits, then re-walks folders the
    // in-flight scan is already about to cover — wasted work for no new information.
    private suspend fun rescanAll() {
        if (!scanMutex.tryLock()) {
            Timber.d("Library Rescan — already in progress, skipping")
            return
        }
        try {
            lastRunAt = System.currentTimeMillis()
            Timber.i("Library Rescan — starting")
            val outcomes = libraryScanner.scanAllEnabled(removeMissing = true)
            val totalAdded = outcomes.sumOf { it.added }
            val totalMissing = outcomes.sumOf { it.markedMissing }
            Timber.i("Library Rescan — done: $totalAdded new, $totalMissing marked missing")
        } finally {
            scanMutex.unlock()
        }
    }

    private companion object {
        const val RESUME_THROTTLE_MS = 5 * 60 * 1000L
        const val MOUNT_DEBOUNCE_MS = 2 * 1000L
    }
}
