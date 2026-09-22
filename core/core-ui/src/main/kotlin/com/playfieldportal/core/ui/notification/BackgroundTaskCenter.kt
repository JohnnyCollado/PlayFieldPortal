package com.playfieldportal.core.ui.notification

import android.content.Context
import com.playfieldportal.core.domain.model.BackgroundTaskInfo
import com.playfieldportal.core.domain.model.NotificationAction
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.model.TaskKind
import com.playfieldportal.core.domain.repository.NotificationRepository
import com.playfieldportal.core.domain.repository.NotificationSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The one place background work is reported, for both sinks at once.
 *
 * Every producer used to build its own [BackgroundTaskNotifier] and post straight to the Android
 * shade — six of them did, scattered across five modules — so anything that was not started from
 * `XMBViewModel` reached the shade and nothing else. The notification panel would sit empty while
 * a metadata scrape, an artwork import or a media scan ran and finished in the shade.
 *
 * Routing every producer through one singleton is what makes the panel truthful. It owns:
 *
 *  - **RUNNING**: [running], an in-memory list. Never persisted (plan §4.2) — a force-stop
 *    mid-scan would otherwise strand a phantom "Scanning… 40%" row with nothing alive left to
 *    finish or fail it, and a scrape of 800 games would be 800 database writes a second apart for
 *    data that is worthless immediately after.
 *  - **EARLIER**: exactly one [NotificationRepository] write per task, at the moment it settles.
 *  - **The shade**: still mirrored, on by default. The panel adds what the shade cannot do on a
 *    HOME-screen device — history, actions, a clear — rather than replacing it.
 *
 * Lives in core-ui so any feature module can report progress without depending on another feature,
 * and takes its two collaborators as core-domain interfaces so it needs no dependency on core-data.
 */
@Singleton
class BackgroundTaskCenter @Inject constructor(
    @ApplicationContext context: Context,
    private val notifications: NotificationRepository,
    settings: NotificationSettings,
) {
    private val shade = BackgroundTaskNotifier(context)

    // Process-lifetime singleton, so its own scope rather than a plumbed one: a task that settles
    // must still get its row written even if the ViewModel that started it is already gone.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Insertion-ordered and synchronized: producers call in from WorkManager threads, the XMB's
    // viewModelScope and scanner coroutines, with no single dispatcher between them.
    private val tasks: MutableMap<String, BackgroundTaskInfo> =
        Collections.synchronizedMap(LinkedHashMap())

    private val _running = MutableStateFlow<List<BackgroundTaskInfo>>(emptyList())

    /** Live background work, in the order it started. The panel's RUNNING section. */
    val running: StateFlow<List<BackgroundTaskInfo>> = _running.asStateFlow()

    // Mirrored into fields because every call below is a discrete event on an arbitrary thread and
    // must not suspend on a DataStore read.
    @Volatile private var recordHistory = true
    @Volatile private var mirrorToShade = true

    init {
        scope.launch { settings.enabled.collect { recordHistory = it } }
        scope.launch { settings.mirrorToShade.collect { mirrorToShade = it } }
    }

    /** Announces a new task. Re-starting a live id replaces it, which is how a retry re-labels. */
    fun start(task: BackgroundTaskInfo) {
        tasks[task.id] = task
        publish()
        if (mirrorToShade) shade.running(task.id, task.label, task.fraction)
    }

    fun start(
        id: String,
        label: String,
        kind: TaskKind,
        current: Int? = null,
        total: Int? = null,
    ) = start(BackgroundTaskInfo(id = id, label = label, kind = kind, current = current, total = total))

    /**
     * A progress tick, taking the operands the producer already reports.
     *
     * Callers used to divide these into a fraction and discard both, which cost the panel — and
     * the shade notification with it — the "14 / 56" and the title being fetched, which is the
     * part anyone actually reads.
     *
     * Unknown ids are ignored rather than resurrected: a tick arriving after the task settled is a
     * race, not a new task, and materialising a row for it would leave one running forever.
     */
    fun progress(id: String, current: Int, total: Int, detail: String? = null) {
        val updated = synchronized(tasks) {
            val task = tasks[id] ?: return
            task.copy(current = current, total = total, detail = detail ?: task.detail)
                .also { tasks[id] = it }
        }
        publish()
        if (mirrorToShade) shade.running(id, updated.label, updated.fraction)
    }

    fun complete(
        id: String,
        message: String? = null,
        action: NotificationAction = NotificationAction.None,
    ) = settle(id, message, NotificationSeverity.SUCCESS, action)

    fun fail(
        id: String,
        message: String,
        action: NotificationAction = NotificationAction.None,
    ) = settle(id, message, NotificationSeverity.ERROR, action)

    /**
     * A one-shot outcome that never had a running phase.
     *
     * Some things worth recording take no measurable time: a shortcut created, a diagnostic
     * copied, a launch that failed before it began. They are not tasks — there was never a bar to
     * show — but they are exactly the kind of fact the history exists to keep, and routing them
     * through [settle] means they get the same dedupe and the same two sinks as everything else.
     */
    fun report(
        id: String,
        label: String,
        message: String? = null,
        severity: NotificationSeverity = NotificationSeverity.INFO,
        kind: NotificationKind = NotificationKind.SYSTEM,
        action: NotificationAction = NotificationAction.None,
    ) = settle(id, message, severity, action, label = label, kind = kind)

    /**
     * Drops a task without recording anything.
     *
     * For work that ended in a way not worth a history row — an automatic pass that found nothing,
     * a cancellation the user performed themselves and already knows about.
     */
    fun cancel(id: String) {
        tasks.remove(id)
        publish()
        shade.cancel(id)
    }

    /**
     * The single point where a task leaves RUNNING and becomes history: one database write per
     * task, at the moment it settles, which is the whole reason progress can stay transient.
     *
     * The row is keyed by the task id, so a Memory Card that fails on four consecutive scans is
     * one unread row rather than a pile of four.
     */
    private fun settle(
        id: String,
        message: String?,
        severity: NotificationSeverity,
        action: NotificationAction,
        label: String? = null,
        kind: NotificationKind? = null,
    ) {
        val task = tasks.remove(id)
        publish()
        val title = label
            ?: task?.label?.trimEnd(ELLIPSIS, '.', ' ')
            ?: if (severity == NotificationSeverity.ERROR) "Task failed" else "Done"
        if (mirrorToShade) {
            if (severity == NotificationSeverity.ERROR) shade.failed(id, title, message.orEmpty())
            else shade.complete(id, title, message)
        }
        if (!recordHistory) return
        scope.launch {
            runCatching {
                notifications.post(
                    kind = kind ?: (task?.kind ?: TaskKind.SCAN).notificationKind,
                    severity = severity,
                    // Both halves on one line: the message alone loses which card it was about,
                    // and the label alone loses what happened.
                    title = message?.takeIf { it.isNotBlank() }?.let { "$title — $it" } ?: title,
                    body = message,
                    sourceKey = "task:$id",
                    action = action,
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
