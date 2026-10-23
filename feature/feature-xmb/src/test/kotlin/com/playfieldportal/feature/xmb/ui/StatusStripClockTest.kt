package com.playfieldportal.feature.xmb.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The status strip's clock follows the device's 12/24-hour setting.
 *
 * Survey U16 (docs/audits/codebase-survey-2026-10-22.md): the time was hard-coded "h:mm a" and the
 * date "MM/dd/yyyy", so a 24-hour or non-US device still saw "3:05 PM" and month/day/year.
 */
class StatusStripClockTest {

    private val afternoon = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(2026, Calendar.OCTOBER, 23, 15, 5)
    }.time

    private fun format(pattern: String) =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(afternoon)

    @Test
    fun `a 24-hour device reads 15 05`() {
        assertEquals("15:05", format(statusTimePattern(is24Hour = true)))
    }

    @Test
    fun `a 12-hour device reads 3 05 PM`() {
        assertEquals("3:05 PM", format(statusTimePattern(is24Hour = false)))
    }

    @Test
    fun `the date asks for a four-digit year in the locale's own order`() {
        assertEquals("MMddyyyy", STATUS_DATE_SKELETON)
    }
}
