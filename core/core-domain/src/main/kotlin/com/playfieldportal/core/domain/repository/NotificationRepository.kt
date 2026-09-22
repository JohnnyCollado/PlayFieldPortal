package com.playfieldportal.core.domain.repository

import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.model.PfpNotification
import kotlinx.coroutines.flow.Flow

/**
 * The durable half of the notification panel: what already happened, newest first.
 *
 * Running work never reaches here — it stays in the ViewModel's in-memory task map and writes one
 * row when it settles. That is what makes [clearAll] safe to offer unconditionally: emptying the
 * history can never cancel a scan, because a scan was never a row (plan §4.2).
 *
 * The single `post` seam lives in core-domain so any feature can produce a notification without
 * depending on another feature (ARCHITECTURE.md).
 */
interface NotificationRepository {

    fun observeAll(): Flow<List<PfpNotification>>

    fun observeUnreadCount(): Flow<Int>

    /**
     * Records a settled outcome and returns its row id.
     *
     * A non-null [sourceKey] replaces the row already holding that key, resetting both its
     * creation time and its read state — the fourth consecutive failure of one Memory Card should
     * read as one fresh unread row, not a pile.
     *
     * Retention runs here, on the write path, rather than on a timer: ADR-0002 rules out a
     * background watcher, and an insert is the only moment the history can grow.
     */
    suspend fun post(
        kind: NotificationKind,
        severity: NotificationSeverity,
        title: String,
        body: String? = null,
        sourceKey: String? = null,
        action: NotificationAction = NotificationAction.None,
        payload: String? = null,
    ): Long

    suspend fun markRead(id: Long)

    suspend fun markUnread(id: Long)

    suspend fun markAllRead()

    suspend fun delete(id: Long)

    /** Empties the history. Never touches running work. */
    suspend fun clearAll()

    /** Drops read rows only — unread survive, which is the point of the entry. */
    suspend fun clearRead()
}

/**
 * The two switches the producer side needs, as flows.
 *
 * A one-interface seam in core-domain rather than a direct DataStore read, so the shared
 * `BackgroundTaskCenter` can live in core-ui and still honour Settings ▸ Notifications without
 * core-ui gaining a dependency on core-data.
 */
interface NotificationSettings {
    /** Whether settled work is recorded at all. Off stops new rows; running work still shows. */
    val enabled: Flow<Boolean>

    /** Whether background work is still mirrored to the Android shade. On by default. */
    val mirrorToShade: Flow<Boolean>
}

/**
 * How long the history is kept, read fresh on every write.
 *
 * A one-method seam rather than a direct DataStore read inside the repository, so the retention
 * policy is exercisable in a JVM test without standing up Preferences.
 */
interface NotificationRetention {
    /** Rows older than this are dropped on the next post. 0 = never auto-clear. */
    suspend fun autoClearDays(): Int

    /** The hard cap on stored rows; read rows are evicted before unread ones. */
    suspend fun maxRows(): Int = DEFAULT_MAX_ROWS

    companion object {
        const val DEFAULT_MAX_ROWS = 200
        const val DEFAULT_AUTO_CLEAR_DAYS = 30
    }
}
