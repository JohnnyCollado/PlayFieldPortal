package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.domain.model.NotificationSeverity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What Import All reports.
 *
 * Survey L26 (docs/audits/codebase-survey-2026-10-22.md): failures were only logged, so the tray
 * said "Imported 0 PC game(s)" as a success when every import failed.
 */
class PcImportAllSummaryTest {

    @Test
    fun `all imported is a success`() {
        assertEquals("Imported 3 PC games into Windows Games." to NotificationSeverity.SUCCESS, pcImportAllSummary(added = 3, failed = 0))
        assertEquals("Imported 1 PC game into Windows Games." to NotificationSeverity.SUCCESS, pcImportAllSummary(added = 1, failed = 0))
    }

    @Test
    fun `some failed is a warning that says how many`() {
        assertEquals(
            "Imported 2 PC games into Windows Games. 1 couldn't be imported." to NotificationSeverity.WARNING,
            pcImportAllSummary(added = 2, failed = 1),
        )
    }

    @Test
    fun `all failed is an error`() {
        assertEquals(
            "None of the 4 PC games could be imported." to NotificationSeverity.ERROR,
            pcImportAllSummary(added = 0, failed = 4),
        )
    }
}
