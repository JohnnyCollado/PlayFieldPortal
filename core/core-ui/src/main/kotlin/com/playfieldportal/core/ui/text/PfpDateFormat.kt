package com.playfieldportal.core.ui.text

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The user-visible date and time shapes the app uses. Each follows the device language and 12/24-hour setting. */
enum class PfpDateStyle(internal val dateSkeleton: String, internal val hasTime: Boolean) {
    /** Day, abbreviated month and year: "Nov 14, 2026". */
    Date("yMMMd", false),

    /** Day and abbreviated month, no year: "Nov 14". */
    ShortDate("MMMd", false),

    /** Hour and minute only: "3:45 PM" or "15:45". */
    Time("", true),

    /** Date with year, then time: "Nov 14, 2026, 3:45 PM". */
    DateTime("yMMMd", true),

    /** Date without the year, then time: "Nov 14, 3:45 PM". */
    ShortDateTime("MMMd", true),
}

/**
 * The one place user-visible dates and times are formatted. Patterns come from
 * [DateFormat.getBestDateTimePattern], so field order, separators, month names and digits follow the
 * locale. Machine formats (file names, API timestamps) stay out of here on purpose.
 *
 * Locale, 24-hour flag and zone are parameters so tests can pin them; production callers use the
 * [Context] overload, which reads them at the moment of use (never cache a formatter across a
 * language or clock-setting change).
 */
object PfpDateFormat {

    /**
     * The skeleton passed to the platform for [style]. [is24Hour] picks `H` over `h` and only matters
     * when the style has a time; null uses `j`, the locale's own preferred hour cycle.
     */
    internal fun skeleton(style: PfpDateStyle, is24Hour: Boolean?): String {
        val time = if (!style.hasTime) "" else when (is24Hour) {
            true -> "Hm"
            false -> "hm"
            null -> "jm"
        }
        return style.dateSkeleton + time
    }

    fun pattern(style: PfpDateStyle, locale: Locale, is24Hour: Boolean?): String =
        DateFormat.getBestDateTimePattern(locale, skeleton(style, is24Hour))

    /** [is24Hour] is ignored by the date-only styles. */
    fun format(
        style: PfpDateStyle,
        epochMillis: Long,
        locale: Locale = Locale.getDefault(),
        is24Hour: Boolean? = false,
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        val formatter = SimpleDateFormat(pattern(style, locale, is24Hour), locale)
        formatter.timeZone = zone
        return formatter.format(Date(epochMillis))
    }

    /** Production entry: current default locale and the device's 24-hour setting, read now. */
    fun format(context: Context, style: PfpDateStyle, epochMillis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        format(style, epochMillis, Locale.getDefault(), DateFormat.is24HourFormat(context), zone)
}
