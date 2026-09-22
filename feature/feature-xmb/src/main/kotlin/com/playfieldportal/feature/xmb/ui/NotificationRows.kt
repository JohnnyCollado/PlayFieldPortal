package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.core.domain.model.PfpNotification
import com.playfieldportal.core.domain.model.BackgroundTaskInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The notification panel's list, built as plain data so the split between the two sections, the
 * cursor's skip rule and the timestamp switch are all testable without composing anything.
 *
 * RUNNING is live and transient, EARLIER is the durable history. The split is not cosmetic — it is
 * the difference between in-memory state and a Room row, and it is why Clear All can empty the
 * history without ever touching a scan (plan §4.2).
 */
sealed interface NotificationRow {

    /** Whether the ▲/▼ cursor may land here. Running rows are a readout, not a list you act on. */
    val isSelectable: Boolean get() = false

    data class Header(val title: String) : NotificationRow

    data class Running(val task: BackgroundTaskInfo) : NotificationRow

    data class History(val notification: PfpNotification) : NotificationRow {
        override val isSelectable: Boolean get() = true
    }

    /** Nothing running and nothing recorded — the panel still opens and says so. */
    data object Empty : NotificationRow
}

const val RUNNING_HEADER = "RUNNING"
const val EARLIER_HEADER = "EARLIER"
const val EMPTY_PANEL_MESSAGE = "No notifications"

/**
 * RUNNING above EARLIER, each behind its own header, headers omitted when their section is empty.
 *
 * [history] is expected newest-first (the DAO's own order) and is not re-sorted here: the panel
 * must show what the database shows, or a dedupe that resets a row's timestamp would appear to do
 * nothing.
 */
fun buildNotificationRows(
    running: List<BackgroundTaskInfo>,
    history: List<PfpNotification>,
): List<NotificationRow> {
    if (running.isEmpty() && history.isEmpty()) return listOf(NotificationRow.Empty)
    return buildList {
        if (running.isNotEmpty()) {
            add(NotificationRow.Header(RUNNING_HEADER))
            running.forEach { add(NotificationRow.Running(it)) }
        }
        if (history.isNotEmpty()) {
            add(NotificationRow.Header(EARLIER_HEADER))
            history.forEach { add(NotificationRow.History(it)) }
        }
    }
}

/** Positions in [this] the cursor may occupy, in order. Empty when there is nothing to act on. */
fun List<NotificationRow>.selectableIndices(): List<Int> =
    mapIndexedNotNull { index, row -> index.takeIf { row.isSelectable } }

/**
 * Moves the cursor by [delta] over the selectable rows only, clamping at both ends.
 *
 * Clamping rather than wrapping: the panel is a short list read top-down, and wrapping from the
 * oldest entry back to the newest reads as a glitch rather than as navigation.
 */
fun List<NotificationRow>.moveCursor(cursor: Int, delta: Int): Int {
    val selectable = selectableIndices()
    if (selectable.isEmpty()) return cursor
    val here = selectable.indexOf(cursor).takeIf { it >= 0 } ?: 0
    return selectable[(here + delta).coerceIn(0, selectable.lastIndex)]
}

/** The first row the cursor can occupy, or -1 when nothing is actionable. */
fun List<NotificationRow>.firstSelectableIndex(): Int = selectableIndices().firstOrNull() ?: -1

/** Keeps [cursor] on a selectable row after the list changes underneath it (a clear, a new post). */
fun List<NotificationRow>.clampCursor(cursor: Int): Int {
    val selectable = selectableIndices()
    if (selectable.isEmpty()) return -1
    if (cursor in selectable) return cursor
    return selectable.lastOrNull { it <= cursor } ?: selectable.first()
}

/** The notification under the cursor, or null when the cursor is parked on nothing actionable. */
fun List<NotificationRow>.notificationAt(cursor: Int): PfpNotification? =
    (getOrNull(cursor) as? NotificationRow.History)?.notification

/**
 * Relative while recent, absolute once older — the Vita's own rule.
 *
 * The switch is at 24 hours: inside a day "6 Hours Ago" is the more useful reading, and past it
 * the hour count stops meaning anything a user can place.
 */
fun notificationTimestamp(
    createdAt: Long,
    now: Long = System.currentTimeMillis(),
    locale: Locale = Locale.getDefault(),
): String {
    val elapsed = now - createdAt
    // A clock change (or a row written a moment "ahead") must not render as "-3 Minutes Ago".
    if (elapsed < 0) return "Just Now"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
    return when {
        minutes < 1L -> "Just Now"
        minutes == 1L -> "1 Minute Ago"
        hours < 1L -> "$minutes Minutes Ago"
        hours == 1L -> "1 Hour Ago"
        hours < 24L -> "$hours Hours Ago"
        else -> SimpleDateFormat("M/d h:mm a", locale).format(Date(createdAt))
    }
}
