package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.model.PfpNotification
import com.playfieldportal.core.domain.model.BackgroundTaskInfo
import com.playfieldportal.core.domain.model.TaskKind
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationRowsTest {

    private fun task(id: String) =
        BackgroundTaskInfo(id = id, label = "Scanning $id", kind = TaskKind.SCAN)

    private fun note(id: Long, createdAt: Long = id, read: Boolean = false) = PfpNotification(
        id = id,
        kind = NotificationKind.SCAN,
        severity = NotificationSeverity.INFO,
        title = "row $id",
        createdAt = createdAt,
        readAt = if (read) createdAt else null,
    )

    // ── Shape ─────────────────────────────────────────────────────────────────

    @Test
    fun `RUNNING sorts above EARLIER, each behind its own header`() {
        val rows = buildNotificationRows(listOf(task("psx")), listOf(note(1), note(2)))

        assertEquals(NotificationRow.Header(RUNNING_HEADER), rows[0])
        assertTrue(rows[1] is NotificationRow.Running)
        assertEquals(NotificationRow.Header(EARLIER_HEADER), rows[2])
        assertTrue(rows[3] is NotificationRow.History)
        assertTrue(rows[4] is NotificationRow.History)
    }

    @Test
    fun `an empty section takes its header with it`() {
        val runningOnly = buildNotificationRows(listOf(task("psx")), emptyList())
        assertTrue(runningOnly.none { it == NotificationRow.Header(EARLIER_HEADER) })

        val historyOnly = buildNotificationRows(emptyList(), listOf(note(1)))
        assertTrue(historyOnly.none { it == NotificationRow.Header(RUNNING_HEADER) })
    }

    @Test
    fun `nothing running and nothing recorded still opens, and says so`() {
        assertEquals(listOf(NotificationRow.Empty), buildNotificationRows(emptyList(), emptyList()))
    }

    @Test
    fun `history is shown in the order it was handed over, newest first`() {
        // The DAO already orders newest-first; re-sorting here would hide a dedupe that reset a
        // row's timestamp and moved it to the top.
        val rows = buildNotificationRows(emptyList(), listOf(note(3), note(2), note(1)))
        val ids = rows.filterIsInstance<NotificationRow.History>().map { it.notification.id }
        assertEquals(listOf(3L, 2L, 1L), ids)
    }

    // ── The cursor ────────────────────────────────────────────────────────────

    @Test
    fun `running rows and headers are skipped by the cursor`() {
        val rows = buildNotificationRows(listOf(task("psx"), task("snes")), listOf(note(1), note(2)))

        // Header, Running, Running, Header, History, History -> only the last two are selectable.
        assertEquals(listOf(4, 5), rows.selectableIndices())
        assertEquals(4, rows.firstSelectableIndex())
    }

    @Test
    fun `the cursor clamps at both ends instead of wrapping`() {
        val rows = buildNotificationRows(emptyList(), listOf(note(1), note(2), note(3)))
        val first = rows.firstSelectableIndex()

        assertEquals(first, rows.moveCursor(first, -1), "up at the top stays at the top")

        var cursor = first
        repeat(5) { cursor = rows.moveCursor(cursor, +1) }
        assertEquals(rows.selectableIndices().last(), cursor, "down past the end stays at the end")
    }

    @Test
    fun `a panel with nothing actionable reports no cursor at all`() {
        val runningOnly = buildNotificationRows(listOf(task("psx")), emptyList())
        assertEquals(-1, runningOnly.firstSelectableIndex())
        assertEquals(-1, runningOnly.clampCursor(0))

        val empty = buildNotificationRows(emptyList(), emptyList())
        assertEquals(-1, empty.firstSelectableIndex())
    }

    @Test
    fun `clampCursor rescues a cursor left past the end by a clear`() {
        val before = buildNotificationRows(emptyList(), listOf(note(1), note(2), note(3)))
        val bottom = before.selectableIndices().last()

        val after = buildNotificationRows(emptyList(), listOf(note(1)))

        assertEquals(after.selectableIndices().last(), after.clampCursor(bottom))
    }

    @Test
    fun `notificationAt reads only history rows`() {
        val rows = buildNotificationRows(listOf(task("psx")), listOf(note(7)))
        assertNull(rows.notificationAt(1), "a running row carries no notification")
        assertEquals(7L, rows.notificationAt(3)?.id)
        assertNull(rows.notificationAt(99), "an out-of-range cursor is not a crash")
    }

    // ── Timestamps ────────────────────────────────────────────────────────────

    @Test
    fun `timestamps read relative while recent and absolute once older`() {
        val now = 1_700_000_000_000L
        fun ago(unit: TimeUnit, amount: Long) =
            notificationTimestamp(now - unit.toMillis(amount), now, Locale.US)

        assertEquals("Just Now", ago(TimeUnit.SECONDS, 20))
        assertEquals("1 Minute Ago", ago(TimeUnit.MINUTES, 1))
        assertEquals("45 Minutes Ago", ago(TimeUnit.MINUTES, 45))
        assertEquals("1 Hour Ago", ago(TimeUnit.HOURS, 1))
        assertEquals("6 Hours Ago", ago(TimeUnit.HOURS, 6))

        // Past a day the hour count stops meaning anything a user can place, so it switches.
        val old = ago(TimeUnit.HOURS, 30)
        assertTrue(!old.endsWith("Ago"), "expected an absolute date past 24h, got $old")
        assertTrue(old.contains("/"), "expected an absolute date past 24h, got $old")
    }

    @Test
    fun `a row written a moment ahead of the clock does not render as negative`() {
        val now = 1_700_000_000_000L
        assertEquals("Just Now", notificationTimestamp(now + 5_000, now, Locale.US))
    }
}
