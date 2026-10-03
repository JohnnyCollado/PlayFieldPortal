package com.playfieldportal.core.ui.notification

import com.playfieldportal.core.domain.model.BackgroundTaskInfo
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationDetail
import com.playfieldportal.core.domain.model.NotificationDetailCodec
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.model.TaskKind
import com.playfieldportal.core.domain.repository.NotificationRepository
import com.playfieldportal.core.domain.repository.NotificationSettings
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundPlayer
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The one place background work is reported.
 *
 * Every producer used to build its own notifier and post straight to the Android shade, so anything
 * not started from `XMBViewModel` never reached the panel. Routing every producer through one
 * singleton is what makes the panel truthful. It owns:
 *
 *  - **RUNNING**: [running], an in-memory list. Never persisted (panel plan §4.2) — a force-stop
 *    mid-scan would otherwise strand a phantom "Scanning… 40%" row with nothing alive left to
 *    finish or fail it, and a scrape of 800 games would be 800 database writes for data that is
 *    worthless immediately after.
 *  - **EARLIER**: exactly one [NotificationRepository] write per task, at the moment it settles,
 *    carrying the task's [NotificationDetail] (Notes or Results) in its payload.
 *  - **Stop**: a producer that registers a handler at [start] can be stopped from the panel through
 *    [requestStop]; it then settles with [stopped].
 *
 * PFP's notifications are launcher-only: nothing here reaches the Android shade any more (the
 * notification details plan §9).
 *
 * Lives in core-ui so any feature module can report progress without depending on another feature,
 * and takes its collaborators as core-domain interfaces so it needs no dependency on core-data.
 */
@Singleton
class BackgroundTaskCenter @Inject constructor(
    private val notifications: NotificationRepository,
    settings: NotificationSettings,
    // The one place a tray notification rings. A row settling here is exactly "a notification
    // popped up", so the cue fires here and nowhere else (no chimes on automatic rescans or backups).
    private val menuSound: MenuSoundPlayer,
) {
    // Process-lifetime singleton, so its own scope rather than a plumbed one: a task that settles
    // must still get its row written even if the ViewModel that started it is already gone.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Insertion-ordered and synchronized: producers call in from WorkManager threads, the XMB's
    // viewModelScope and scanner coroutines, with no single dispatcher between them. The stop
    // handles share the same lock so a task and its handle can never disagree.
    private val tasks: MutableMap<String, BackgroundTaskInfo> =
        Collections.synchronizedMap(LinkedHashMap())
    private val stopHandles = HashMap<String, () -> Unit>()

    private val _running = MutableStateFlow<List<BackgroundTaskInfo>>(emptyList())

    /** Live background work, in the order it started. The panel's RUNNING section. */
    val running: StateFlow<List<BackgroundTaskInfo>> = _running.asStateFlow()

    // Mirrored into a field because every call below is a discrete event on an arbitrary thread and
    // must not suspend on a DataStore read.
    @Volatile private var recordHistory = true

    init {
        scope.launch { settings.enabled.collect { recordHistory = it } }
    }

    /**
     * Announces a new task. Re-starting a live id replaces it, which is how a retry re-labels.
     *
     * [onStop] makes the task stoppable from the panel; it should end the work (cancel the job,
     * cancel the unique work) so the producer can settle with [stopped]. Work that must not stop
     * halfway (a restore, an artwork move) passes none.
     */
    fun start(task: BackgroundTaskInfo, onStop: (() -> Unit)? = null) {
        synchronized(tasks) {
            tasks[task.id] = task.copy(stoppable = onStop != null, stopping = false)
            if (onStop != null) stopHandles[task.id] = onStop else stopHandles.remove(task.id)
        }
        publish()
    }

    fun start(
        id: String,
        label: String,
        kind: TaskKind,
        current: Int? = null,
        total: Int? = null,
        onStop: (() -> Unit)? = null,
        stopNote: String? = null,
    ) = start(
        BackgroundTaskInfo(id = id, label = label, kind = kind, current = current, total = total, stopNote = stopNote),
        onStop,
    )

    /**
     * A progress tick, taking the operands the producer already reports.
     *
     * Unknown ids are ignored rather than resurrected: a tick arriving after the task settled is a
     * race, not a new task. Ticks for a task that is stopping are ignored too, so its bar freezes
     * where the user asked it to stop.
     */
    fun progress(id: String, current: Int, total: Int, detail: String? = null) {
        synchronized(tasks) {
            val task = tasks[id] ?: return
            if (task.stopping) return
            tasks[id] = task.copy(current = current, total = total, detail = detail ?: task.detail)
        }
        publish()
    }

    fun complete(
        id: String,
        message: String? = null,
        action: NotificationAction = NotificationAction.None,
        detail: NotificationDetail? = null,
        title: String? = null,
    ) = settle(id, message, NotificationSeverity.SUCCESS, action, detail, label = title)

    fun fail(
        id: String,
        message: String,
        action: NotificationAction = NotificationAction.None,
        detail: NotificationDetail? = null,
        title: String? = null,
    ) = settle(id, message, NotificationSeverity.ERROR, action, detail, label = title)

    /**
     * A one-shot outcome that never had a running phase.
     *
     * Some things worth recording take no measurable time: a shortcut created, a diagnostic
     * copied, a launch that failed before it began. Routing them through [settle] means they get
     * the same dedupe, the same cue and the same detail payload as everything else.
     */
    fun report(
        id: String,
        label: String,
        message: String? = null,
        severity: NotificationSeverity = NotificationSeverity.INFO,
        kind: NotificationKind = NotificationKind.SYSTEM,
        action: NotificationAction = NotificationAction.None,
        detail: NotificationDetail? = null,
        read: Boolean = false,
    ) = settle(id, message, severity, action, detail, label = label, kind = kind, read = read)

    /**
     * The user asked to stop [id] from the panel. Freezes the row as "Stopping…" and calls the
     * producer's handler once. Returns false — and does nothing — for a task that is gone, has no
     * handler, or is already stopping.
     */
    fun requestStop(id: String): Boolean {
        val handle = synchronized(tasks) {
            val task = tasks[id] ?: return false
            if (!task.stoppable || task.stopping) return false
            tasks[id] = task.copy(stopping = true)
            stopHandles[id]
        } ?: return false
        publish()
        runCatching(handle).onFailure { Timber.w(it, "Stop handler for task %s threw", id) }
        return true
    }

    /**
     * The producer finished stopping. Records a WARNING row that is already read and makes no
     * sound — the user stopped it themselves and is looking at the panel — with whatever [detail]
     * says got done before the stop.
     */
    fun stopped(
        id: String,
        message: String? = null,
        action: NotificationAction = NotificationAction.None,
        detail: NotificationDetail? = null,
        title: String? = null,
    ) = settle(id, message, NotificationSeverity.WARNING, action, detail, label = title, read = true)

    /** True while [id] is running and the user has asked it to stop. */
    fun isStopping(id: String): Boolean = synchronized(tasks) { tasks[id]?.stopping == true }

    /**
     * [start], stoppable by cancelling the calling coroutine. The common shape for a producer that
     * runs its work in one coroutine: pair it with [settleCancelled] in a `CancellationException`
     * catch so a stop records what got done.
     */
    suspend fun startStoppable(
        id: String,
        label: String,
        kind: TaskKind,
        current: Int? = null,
        total: Int? = null,
        stopNote: String? = null,
    ) {
        val job = currentCoroutineContext().job
        start(id, label, kind, current, total, onStop = { job.cancel() }, stopNote = stopNote)
    }

    /**
     * Settles a producer whose coroutine was cancelled: a stop the user asked for records a quiet
     * [stopped] row with what got done; any other cancellation (the app shutting work down) drops
     * the task, because nobody asked for that and there is nothing to tell them.
     */
    fun settleCancelled(
        id: String,
        message: String? = null,
        action: NotificationAction = NotificationAction.None,
        detail: NotificationDetail? = null,
        title: String? = null,
    ) {
        if (isStopping(id)) stopped(id, message, action, detail, title) else cancel(id)
    }

    /**
     * Drops a task without recording anything.
     *
     * For work that ended in a way not worth a history row — an automatic pass that found nothing.
     */
    fun cancel(id: String) {
        synchronized(tasks) {
            tasks.remove(id)
            stopHandles.remove(id)
        }
        publish()
    }

    /**
     * The single point where a task leaves RUNNING and becomes history: one database write per
     * task, keyed by the task id, so a Memory Card that fails on four consecutive scans is one row.
     *
     * The title and the message stay separate: the panel shows the title on one line and the sheet
     * shows the rest.
     */
    private fun settle(
        id: String,
        message: String?,
        severity: NotificationSeverity,
        action: NotificationAction,
        detail: NotificationDetail?,
        label: String? = null,
        kind: NotificationKind? = null,
        read: Boolean = false,
    ) {
        val task = synchronized(tasks) {
            stopHandles.remove(id)
            tasks.remove(id)
        }
        publish()
        val title = label
            ?: task?.label?.trimEnd(ELLIPSIS, '.', ' ')
            ?: if (severity == NotificationSeverity.ERROR) "Task failed" else "Done"
        if (!recordHistory) return
        // A row is about to land in the panel — ring the notification cue, unless the user already
        // knows (a read row: a stop they asked for). Fired off the DB write so it is not held by IO.
        if (!read) menuSound.play(MenuSound.NOTIFICATION)
        val payload = detail?.let(NotificationDetailCodec::encode)
        scope.launch {
            runCatching {
                notifications.post(
                    kind = kind ?: (task?.kind ?: TaskKind.SCAN).notificationKind,
                    severity = severity,
                    title = title,
                    body = message?.takeIf { it.isNotBlank() },
                    sourceKey = "task:$id",
                    action = action,
                    payload = payload,
                    read = read,
                )
            }.onFailure { Timber.w(it, "Could not record the notification for task %s", id) }
        }
    }

    private fun publish() {
        _running.value = synchronized(tasks) { tasks.values.toList() }
    }

    private companion object {
        /** The trailing character on a running label ("Scanning PlayStation…"), trimmed at settle. */
        const val ELLIPSIS = '…'
    }
}
