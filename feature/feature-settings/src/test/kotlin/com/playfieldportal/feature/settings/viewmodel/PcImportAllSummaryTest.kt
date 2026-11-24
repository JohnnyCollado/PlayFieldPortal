package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.domain.model.NotificationSeverity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What Import All reports.
 *
 * Survey L26 (docs/audits/codebase-survey-2026-10-22.md): failures were only logged, so the tray
 * said "Imported 0 PC game(s)" as a success when every import failed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PcImportAllSummaryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `all imported is a success`() {
        assertEquals("Imported 3 PC games into Windows Games." to NotificationSeverity.SUCCESS, pcImportAllSummary(context, added = 3, failed = 0))
        assertEquals("Imported 1 PC game into Windows Games." to NotificationSeverity.SUCCESS, pcImportAllSummary(context, added = 1, failed = 0))
    }

    @Test
    fun `some failed is a warning that says how many`() {
        assertEquals(
            "Imported 2 PC games into Windows Games. 1 couldn't be imported." to NotificationSeverity.WARNING,
            pcImportAllSummary(context, added = 2, failed = 1),
        )
    }

    @Test
    fun `all failed is an error`() {
        assertEquals(
            "None of the 4 PC games could be imported." to NotificationSeverity.ERROR,
            pcImportAllSummary(context, added = 0, failed = 4),
        )
    }
}
