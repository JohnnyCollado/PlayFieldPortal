package com.playfieldportal.core.data.database.seeder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "done" flag of a one-time startup migration is written only when its work succeeded.
 *
 * Survey L2 (docs/audits/codebase-survey-2026-10-22.md): LibraryConsolidation and StartupDataPrep
 * swallowed the failure and then marked the migration done, so a half-finished pass never retried.
 */
class OneShotStepTest {

    @Test
    fun `successful work reports success`() = runTest {
        var ran = false
        assertTrue(runOneShotStep("test") { ran = true })
        assertTrue(ran)
    }

    @Test
    fun `failing work reports failure instead of throwing`() = runTest {
        assertFalse(runOneShotStep("test") { error("database is locked") })
    }

    @Test
    fun `the done flag is written only after success`() = runTest {
        var marked = 0
        if (runOneShotStep("ok") { }) marked++
        if (runOneShotStep("fails") { error("io") }) marked++
        assertEquals(1, marked)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is rethrown, not treated as a failure`() = runTest {
        runOneShotStep("cancelled") { throw CancellationException("scope cancelled") }
    }
}
