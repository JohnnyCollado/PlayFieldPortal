package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationDetail
import com.playfieldportal.core.domain.model.PfpErrorCode
import com.playfieldportal.core.domain.model.ResultOutcome
import com.playfieldportal.core.domain.model.toNotificationAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scan's outcomes as the panel shows them: one Results item per card, with the right code. */
class ScanReportingTest {

    private fun outcome(status: ScanStatus, error: String? = null, added: Int = 0, missing: Int = 0) =
        PlatformScanOutcome("psp", "PSP", status, added = added, markedMissing = missing, errorMessage = error)

    @Test
    fun `each status maps to its code`() {
        assertEquals(PfpErrorCode.SC_1001, outcome(ScanStatus.SKIPPED_NO_SOURCE).errorCode())
        assertEquals(PfpErrorCode.SC_1002, outcome(ScanStatus.SKIPPED_BUSY).errorCode())
        assertEquals(PfpErrorCode.SC_9001, outcome(ScanStatus.FAILED, "boom").errorCode())
        assertNull(outcome(ScanStatus.COMPLETED).errorCode())
    }

    @Test
    fun `a lost permission and a vanished folder get their own codes`() {
        assertEquals(PfpErrorCode.SC_2001, outcome(ScanStatus.FAILED, "Permission denial reading tree").errorCode())
        assertEquals(PfpErrorCode.SC_2002, outcome(ScanStatus.FAILED, "Folder not found: /storage/x").errorCode())
    }

    @Test
    fun `a card outcome becomes an item that opens its card`() {
        val failed = outcome(ScanStatus.FAILED, "Folder not found").toResultItem()
        assertEquals(ResultOutcome.FAILED, failed.outcome)
        assertEquals("SC-2002", failed.code)
        assertEquals(NotificationAction.OpenMemoryCard("psp"), failed.action?.toNotificationAction())

        val skipped = outcome(ScanStatus.SKIPPED_NO_SOURCE).toResultItem()
        assertEquals(ResultOutcome.SKIPPED, skipped.outcome)

        val done = outcome(ScanStatus.COMPLETED, added = 12, missing = 1).toResultItem()
        assertEquals(ResultOutcome.DONE, done.outcome)
        assertEquals("+12", done.badge)
        assertTrue(done.reason!!.contains("1 marked missing"))
    }

    @Test
    fun `scan results put failures first and label successes Added`() {
        val results = scanResults(
            listOf(outcome(ScanStatus.COMPLETED, added = 3), outcome(ScanStatus.FAILED, "x")),
        )
        assertEquals(ResultOutcome.FAILED, results.items.first().outcome)
        assertEquals("Added", results.labels.done)
    }

    @Test
    fun `a failed single card scan is a note with its code`() {
        val notes = outcome(ScanStatus.SKIPPED_NO_SOURCE).failureNotes() as NotificationDetail.Notes
        assertEquals("SC-1001", notes.code)
        assertNull(outcome(ScanStatus.COMPLETED).failureNotes())
    }
}
