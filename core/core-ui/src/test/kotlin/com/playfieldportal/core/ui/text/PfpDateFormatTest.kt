package com.playfieldportal.core.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone

/**
 * Exact strings differ between ICU versions (narrow no-break spaces, abbreviated month spellings),
 * so these pin the parts that matter: field order, 12/24-hour choice, the year being present or
 * absent, and the zone being honoured.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PfpDateFormatTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** 2026-11-14 15:45:00 UTC. */
    private val instant = 1_794_671_100_000L

    private val enUs = Locale.US
    private val enGb = Locale.UK
    private val de = Locale.GERMANY
    private val ja = Locale.JAPAN
    private val ar = Locale.forLanguageTag("ar")

    private fun fmt(style: PfpDateStyle, locale: Locale, is24: Boolean, zone: TimeZone = utc): String =
        latin(PfpDateFormat.format(style, instant, locale, is24, zone))

    /** Maps Arabic-Indic and other locale digits to ASCII so digit assertions hold for every locale. */
    private fun latin(s: String): String = buildString {
        for (c in s) {
            val d = Character.digit(c, 10)
            append(if (d in 0..9) ('0' + d) else c)
        }
    }

    // ── Time ─────────────────────────────────────────────────────────────────

    @Test fun `time en-US 12h shows 3-45 with PM`() {
        val s = fmt(PfpDateStyle.Time, enUs, false)
        assertTrue(s, s.contains("3:45") && s.contains("PM"))
    }

    @Test fun `time en-GB 24h shows 15-45`() = assertTrue(fmt(PfpDateStyle.Time, enGb, true).contains("15:45"))

    @Test fun `time de 24h shows 15-45`() = assertTrue(fmt(PfpDateStyle.Time, de, true).contains("15:45"))

    @Test fun `time ja 24h shows 15-45`() = assertTrue(fmt(PfpDateStyle.Time, ja, true).contains("15:45"))

    @Test fun `time ar 24h shows 15-45 in some digit set`() = assertTrue(fmt(PfpDateStyle.Time, ar, true).contains("15"))

    @Test fun `the 24-hour flag switches the same locale`() {
        assertFalse(fmt(PfpDateStyle.Time, enUs, true).contains("PM"))
        assertTrue(fmt(PfpDateStyle.Time, enUs, true).contains("15:45"))
    }

    @Test fun `the zone is honoured`() {
        val s = fmt(PfpDateStyle.Time, ja, true, TimeZone.getTimeZone("Asia/Tokyo"))
        assertTrue(s, s.contains("0:45"))
    }

    // ── Date ─────────────────────────────────────────────────────────────────

    @Test fun `date en-US puts the month before the day`() {
        val s = fmt(PfpDateStyle.Date, enUs, false)
        assertTrue(s, s.contains("Nov") && s.contains("2026"))
        assertTrue(s, s.indexOf("Nov") < s.indexOf("14"))
    }

    @Test fun `date en-GB puts the day before the month`() {
        val s = fmt(PfpDateStyle.Date, enGb, true)
        assertTrue(s, s.indexOf("14") < s.indexOf("Nov"))
        assertTrue(s, s.contains("2026"))
    }

    @Test fun `date de carries day and year`() {
        val s = fmt(PfpDateStyle.Date, de, true)
        assertTrue(s, s.contains("14") && s.contains("2026"))
    }

    @Test fun `date ja carries year month and day`() {
        val s = fmt(PfpDateStyle.Date, ja, true)
        assertTrue(s, s.contains("2026") && s.contains("11") && s.contains("14"))
    }

    @Test fun `date ar carries year and day`() {
        val s = fmt(PfpDateStyle.Date, ar, true)
        assertTrue(s, s.contains("2026") && s.contains("14"))
    }

    @Test fun `short date has no year and no time`() {
        for (locale in listOf(enUs, enGb, de, ja, ar)) {
            val s = fmt(PfpDateStyle.ShortDate, locale, true)
            assertFalse("$locale: $s", s.contains("2026"))
            assertFalse("$locale: $s", s.contains(":"))
            assertTrue("$locale: $s", s.contains("14"))
        }
    }

    // ── Date and time ────────────────────────────────────────────────────────

    @Test fun `date time en-US 12h has year and PM`() {
        val s = fmt(PfpDateStyle.DateTime, enUs, false)
        assertTrue(s, s.contains("Nov") && s.contains("2026") && s.contains("3:45") && s.contains("PM"))
    }

    @Test fun `date time en-GB 24h has year and 15-45`() {
        val s = fmt(PfpDateStyle.DateTime, enGb, true)
        assertTrue(s, s.contains("2026") && s.contains("15:45"))
    }

    @Test fun `date time de ja and ar carry the year and the 24h hour`() {
        for (locale in listOf(de, ja, ar)) {
            val s = fmt(PfpDateStyle.DateTime, locale, true)
            assertTrue("$locale: $s", s.contains("2026") && s.contains("15"))
        }
    }

    @Test fun `short date time drops the year but keeps the time`() {
        for ((locale, is24) in listOf(enUs to false, enGb to true, de to true, ja to true, ar to true)) {
            val s = fmt(PfpDateStyle.ShortDateTime, locale, is24)
            assertFalse("$locale: $s", s.contains("2026"))
            assertTrue("$locale: $s", s.contains("14"))
            assertTrue("$locale: $s", s.contains(if (is24) "15" else "3"))
        }
        assertTrue(fmt(PfpDateStyle.ShortDateTime, enUs, false).contains("PM"))
    }

    @Test fun `patterns use the 12 or 24 hour field`() {
        assertTrue(PfpDateFormat.pattern(PfpDateStyle.Time, enUs, true).contains("H"))
        assertTrue(PfpDateFormat.pattern(PfpDateStyle.Time, enUs, false).contains("h"))
        assertEquals("yMMMd", PfpDateFormat.skeleton(PfpDateStyle.Date, true))
        assertEquals("MMMdhm", PfpDateFormat.skeleton(PfpDateStyle.ShortDateTime, false))
    }
}
