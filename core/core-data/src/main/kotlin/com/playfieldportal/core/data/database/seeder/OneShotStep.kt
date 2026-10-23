package com.playfieldportal.core.data.database.seeder

import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Runs one pass of a one-time startup migration and reports whether it finished.
 *
 * The caller writes its "done" flag only when this returns true, so a pass that threw part-way
 * runs again on the next launch instead of leaving the library half-migrated forever
 * (survey L2, docs/audits/codebase-survey-2026-10-22.md). Cancellation is not a failure: it is
 * rethrown so the caller's scope can stop.
 */
internal suspend fun runOneShotStep(label: String, work: suspend () -> Unit): Boolean =
    try {
        work()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "$label failed; it will run again on the next launch")
        false
    }
