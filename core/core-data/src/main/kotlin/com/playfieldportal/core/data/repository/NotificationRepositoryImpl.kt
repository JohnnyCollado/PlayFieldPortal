package com.playfieldportal.core.data.repository

import com.playfieldportal.core.data.database.dao.NotificationDao
import com.playfieldportal.core.data.database.entity.NotificationEntity
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.model.PfpNotification
import com.playfieldportal.core.domain.repository.NotificationRepository
import com.playfieldportal.core.domain.repository.NotificationRetention
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class NotificationRepositoryImpl(
    private val dao: NotificationDao,
    private val retention: NotificationRetention,
    /** Pinned by tests so dedupe and the age cutoff can be asserted without waiting on a clock. */
    private val now: () -> Long,
) : NotificationRepository {

    @Inject constructor(dao: NotificationDao, retention: NotificationRetention) :
        this(dao, retention, System::currentTimeMillis)

    override fun observeAll(): Flow<List<PfpNotification>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }

    override fun observeUnreadCount(): Flow<Int> = dao.observeUnreadCount()

    override suspend fun post(
        kind: NotificationKind,
        severity: NotificationSeverity,
        title: String,
        body: String?,
        sourceKey: String?,
        action: NotificationAction,
        payload: String?,
    ): Long {
        // Dedupe: reuse the existing row's id so REPLACE overwrites it in place. created_at and
        // read_at are NOT carried over — a repeat of the same event is a fresh, unread event.
        val existingId = sourceKey?.let { dao.findBySourceKey(it)?.id } ?: 0L
        val id = dao.insert(
            NotificationEntity(
                id = existingId,
                kind = kind.name,
                severity = severity.name,
                title = title,
                body = body,
                sourceKey = sourceKey,
                actionType = action.typeKey,
                actionArg = action.arg,
                payload = payload,
                createdAt = now(),
                readAt = null,
            )
        )
        prune()
        return id
    }

    override suspend fun markRead(id: Long) = dao.setReadAt(id, now())

    override suspend fun markUnread(id: Long) = dao.setReadAt(id, null)

    override suspend fun markAllRead() = dao.markAllRead(now())

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun clearAll() = dao.clearAll()

    override suspend fun clearRead() = dao.clearRead()

    /**
     * Retention, run on the write path because ADR-0002 rules out a background watcher and an
     * insert is the only moment the history can grow.
     *
     * Neither knob is a user-facing guarantee — Clear All is. These just stop an unattended device
     * from accumulating rows forever.
     */
    private suspend fun prune() {
        val days = retention.autoClearDays()
        if (days > 0) dao.deleteOlderThan(now() - days * MILLIS_PER_DAY)
        val excess = dao.count() - retention.maxRows()
        if (excess > 0) dao.evictOldest(excess)
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}

internal fun NotificationEntity.toModel(): PfpNotification = PfpNotification(
    id = id,
    kind = NotificationKind.fromName(kind),
    severity = NotificationSeverity.fromName(severity),
    title = title,
    body = body,
    sourceKey = sourceKey,
    action = NotificationAction.decode(actionType, actionArg),
    payload = payload,
    createdAt = createdAt,
    readAt = readAt,
)
