package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.BackgroundTaskInfo
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationDetail
import com.playfieldportal.core.domain.model.NotificationDetailCodec
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.model.PfpNotification
import com.playfieldportal.core.domain.model.ResultItem
import com.playfieldportal.core.domain.model.ResultOutcome
import com.playfieldportal.core.domain.model.TaskKind
import com.playfieldportal.feature.xmb.ui.buildNotificationRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * What Confirm does on the panel, and how the panel's layered states survive the lists changing
 * under them. The VM applies these; the rules live here so they can be pinned without a VM.
 */
class NotificationPanelLogicTest {

    private fun task(id: String, stoppable: Boolean = true, stopping: Boolean = false) = BackgroundTaskInfo(
        id = id, label = "Scanning $id…", kind = TaskKind.SCAN, current = 14, total = 56,
        stoppable = stoppable, stopping = stopping, stopNote = "Games found so far are kept.",
    )

    private fun row(
        id: Long,
        payload: String? = null,
        body: String? = null,
        action: NotificationAction = NotificationAction.None,
    ) = PfpNotification(
        id = id, kind = NotificationKind.SCAN, severity = NotificationSeverity.ERROR,
        title = "row $id", body = body, action = action, payload = payload, createdAt = id,
    )

    private val notesPayload = NotificationDetailCodec.encode(NotificationDetail.Notes(summary = "Why"))
    private val resultsPayload = NotificationDetailCodec.encode(
        NotificationDetail.Results(items = listOf(ResultItem("PSP", ResultOutcome.FAILED)))
    )

    // ── Confirm ───────────────────────────────────────────────────────────────

    @Test
    fun `confirm on a notes or results row opens its sheet`() {
        val rows = buildNotificationRows(emptyList(), listOf(row(1, notesPayload), row(2, resultsPayload)))
        assertEquals(PanelConfirm.OpenSheet(1), panelConfirm(rows, cursor = 1))
        assertEquals(PanelConfirm.OpenSheet(2), panelConfirm(rows, cursor = 2))
    }

    @Test
    fun `confirm on a simple row runs its action`() {
        val simple = row(3, action = NotificationAction.OpenMemoryCard("psx"))
        val rows = buildNotificationRows(emptyList(), listOf(simple))
        assertEquals(PanelConfirm.RunAction(simple), panelConfirm(rows, cursor = 1))
    }

    @Test
    fun `confirm on a stoppable running row asks before stopping`() {
        val rows = buildNotificationRows(listOf(task("psx")), emptyList())
        val confirm = assertIs<PanelConfirm.AskStop>(panelConfirm(rows, cursor = 1))
        assertEquals("psx", confirm.state.taskId)
        assertEquals("Stop \"Scanning psx\"?", confirm.state.title)
        assertEquals("14 of 56 are done. Games found so far are kept.", confirm.state.message)
    }

    @Test
    fun `confirm on nothing actionable does nothing`() {
        val rows = buildNotificationRows(listOf(task("psx", stoppable = false)), emptyList())
        assertEquals(PanelConfirm.Nothing, panelConfirm(rows, cursor = 1))
        assertEquals(PanelConfirm.Nothing, panelConfirm(rows, cursor = -1))
    }

    @Test
    fun `a stop message without counts is just the keep line`() {
        val state = stopConfirmFor(task("psx").copy(current = null, total = null))
        assertEquals("Games found so far are kept.", state.message)
    }

    // ── Layered states survive the lists changing ─────────────────────────────

    @Test
    fun `a sheet whose row was cleared closes`() {
        val panel = NotificationPanelState(cursor = 1, sheetNotificationId = 9)
        val reconciled = panel.reconcile(running = emptyList(), history = listOf(row(1, notesPayload)))
        assertNull(reconciled.sheetNotificationId)
    }

    @Test
    fun `a sheet whose row is still there stays open`() {
        val panel = NotificationPanelState(cursor = 1, sheetNotificationId = 1)
        assertEquals(1L, panel.reconcile(emptyList(), listOf(row(1, notesPayload))).sheetNotificationId)
    }

    @Test
    fun `a stop confirm closes when its task settles or starts stopping`() {
        val panel = NotificationPanelState(cursor = 1, stopConfirm = stopConfirmFor(task("psx")))
        assertNull(panel.reconcile(running = emptyList(), history = emptyList()).stopConfirm)
        assertNull(panel.reconcile(running = listOf(task("psx", stopping = true)), history = emptyList()).stopConfirm)
        assertEquals("psx", panel.reconcile(listOf(task("psx")), emptyList()).stopConfirm?.taskId)
    }

    @Test
    fun `the cursor is clamped when the row under it goes away`() {
        val panel = NotificationPanelState(cursor = 1)
        val reconciled = panel.reconcile(running = listOf(task("psx", stopping = true)), history = listOf(row(1)))
        assertEquals(3, reconciled.cursor, "the stopping row is no longer selectable")
    }

    // ── Hint selection ────────────────────────────────────────────────────────

    @Test
    fun `the hint names Stop on a running row and Open on a history row`() {
        val rows = buildNotificationRows(listOf(task("psx")), listOf(row(1)))
        assertEquals(PanelSelection.RUNNING, panelSelection(rows, cursor = 1))
        assertEquals(PanelSelection.HISTORY, panelSelection(rows, cursor = 3))
        assertEquals(PanelSelection.NONE, panelSelection(rows, cursor = -1))
    }

    // ── List menu: rows that cannot act are not offered ───────────────────────

    private fun ids(history: List<PfpNotification>) = notificationListMenuItems(history).map { it.id }

    @Test
    fun `an empty history offers no menu at all`() {
        assertEquals(emptyList(), ids(emptyList()))
    }

    @Test
    fun `with nothing read there is no Clear Read`() {
        assertEquals(
            listOf(NotificationMenuIds.MARK_ALL_READ, NotificationMenuIds.CLEAR_ALL),
            ids(listOf(row(1), row(2))),
        )
    }

    @Test
    fun `with nothing unread there is no Mark All Read`() {
        assertEquals(
            listOf(NotificationMenuIds.CLEAR_READ, NotificationMenuIds.CLEAR_ALL),
            ids(listOf(row(1).copy(readAt = 5L))),
        )
    }

    @Test
    fun `a mixed history offers all three with Clear All red and last`() {
        val items = notificationListMenuItems(listOf(row(1), row(2).copy(readAt = 5L)))
        assertEquals(
            listOf(NotificationMenuIds.MARK_ALL_READ, NotificationMenuIds.CLEAR_READ, NotificationMenuIds.CLEAR_ALL),
            items.map { it.id },
        )
        assertEquals(listOf("Mark All Read", "Clear Read", "Clear All"), items.map { it.label })
        assertEquals(listOf(false, false, true), items.map { it.isDestructive })
    }

    // ── Social account menu title ─────────────────────────────────────────────

    @Test
    fun `the account menu is titled with the account name`() {
        assertEquals("Johnny", socialAccountMenuTitle("Johnny"))
        assertEquals("Account", socialAccountMenuTitle(""))
        assertEquals("Account", socialAccountMenuTitle(null))
    }
}
