package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.model.PfpNotification
import com.playfieldportal.core.domain.repository.NotificationRepository
import com.playfieldportal.core.domain.model.BackgroundTaskInfo
import com.playfieldportal.core.domain.model.TaskKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one behaviour a user has to be able to trust: emptying the tray can never cancel a scan.
 *
 * It holds structurally rather than by care — running work is not a row, so `clearAll()` has
 * nothing to reach it with — and this pins that structure. It also pins the other half: a task
 * that settles *after* a clear still posts, so the tray refills rather than staying empty because
 * the user happened to clear it mid-scan.
 */
class ClearDoesNotCancelTest {

    /** In-memory stand-in with the retention policy stripped out; only the ordering matters here. */
    private class FakeNotificationRepository : NotificationRepository {
        private val rows = MutableStateFlow<List<PfpNotification>>(emptyList())
        private var nextId = 1L

        override fun observeAll(): Flow<List<PfpNotification>> = rows
        override fun observeUnreadCount(): Flow<Int> = rows.map { list -> list.count { !it.isRead } }

        override suspend fun post(
            kind: NotificationKind,
            severity: NotificationSeverity,
            title: String,
            body: String?,
            sourceKey: String?,
            action: NotificationAction,
            payload: String?,
        ): Long {
            val id = nextId++
            rows.value = listOf(
                PfpNotification(
                    id = id,
                    kind = kind,
                    severity = severity,
                    title = title,
                    body = body,
                    sourceKey = sourceKey,
                    action = action,
                    payload = payload,
                    createdAt = id,
                )
            ) + rows.value.filterNot { sourceKey != null && it.sourceKey == sourceKey }
            return id
        }

        override suspend fun markRead(id: Long) = update(id) { it.copy(readAt = it.createdAt) }
        override suspend fun markUnread(id: Long) = update(id) { it.copy(readAt = null) }
        override suspend fun markAllRead() {
            rows.value = rows.value.map { it.copy(readAt = it.readAt ?: it.createdAt) }
        }
        override suspend fun delete(id: Long) {
            rows.value = rows.value.filterNot { it.id == id }
        }
        override suspend fun clearAll() { rows.value = emptyList() }
        override suspend fun clearRead() { rows.value = rows.value.filterNot { it.isRead } }

        private inline fun update(id: Long, transform: (PfpNotification) -> PfpNotification) {
            rows.value = rows.value.map { if (it.id == id) transform(it) else it }
        }
    }

    private val repository = FakeNotificationRepository()

    private val running = listOf(
        BackgroundTaskInfo(id = "scan_psx", label = "Scanning PlayStation", kind = TaskKind.SCAN),
        BackgroundTaskInfo(
            id = "scrape_dc",
            label = "Artwork",
            kind = TaskKind.ARTWORK,
            current = 14,
            total = 56,
        ),
    )

    @Test
    fun `clearAll empties the history and leaves running work untouched`() = runTest {
        repository.post(
            NotificationKind.SCAN, NotificationSeverity.SUCCESS, "Added 12 games", sourceKey = "task:scan_snes",
        )
        assertEquals(1, repository.observeAll().first().size)

        repository.clearAll()

        val history = repository.observeAll().first()
        assertEquals(emptyList(), history)

        // The running list is a separate, in-memory thing; the clear had no way to reach it.
        val rows = buildNotificationRows(running, history)
        assertEquals(NotificationRow.Header(RUNNING_HEADER), rows.first())
        assertEquals(2, rows.count { it is NotificationRow.Running })
        assertTrue(rows.none { it is NotificationRow.History })
    }

    @Test
    fun `a task settling after a clear still posts its row`() = runTest {
        repository.post(NotificationKind.SCAN, NotificationSeverity.SUCCESS, "old", sourceKey = "task:a")
        repository.clearAll()

        repository.post(
            NotificationKind.SCAN,
            NotificationSeverity.SUCCESS,
            "Scanning PlayStation — Added 12 games",
            sourceKey = "task:scan_psx",
        )

        val titles = repository.observeAll().first().map { it.title }
        assertEquals(listOf("Scanning PlayStation — Added 12 games"), titles)
    }

    @Test
    fun `the panel still shows the running section after the history is emptied`() = runTest {
        repository.clearAll()
        val rows = buildNotificationRows(running, repository.observeAll().first())

        // Not the empty state: work is still in flight, and saying "No notifications" over a live
        // scan is exactly the "did I just cancel it?" moment this is meant to prevent.
        assertTrue(rows.none { it == NotificationRow.Empty })
    }
}
