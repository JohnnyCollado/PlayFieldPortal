package com.playfieldportal.studio

import java.io.File

/** Every scratch file a state references: motion, UI media and passthrough entries. */
internal fun StudioState.scratchFiles(): Set<File> = buildSet {
    motionFile?.let(::add)
    addAll(mediaFiles.values)
    addAll(passthroughFiles.values)
}

/** The state with everything that is session chrome rather than theme content neutralised. */
private fun StudioState.document(): StudioState = copy(
    busy = false,
    statusMessage = null,
    dialog = null,
    batchProgress = null,
    pendingWallpaper = null,
)

/** This (restored) snapshot's theme content with [current]'s session chrome. */
internal fun StudioState.withSessionOf(current: StudioState): StudioState = copy(
    busy = current.busy,
    statusMessage = current.statusMessage,
    dialog = current.dialog,
    batchProgress = current.batchProgress,
    pendingWallpaper = current.pendingWallpaper,
)

/**
 * Snapshot undo/redo for [StudioState], and the owner of scratch-file lifetime.
 *
 * A scratch file (motion, media, passthrough) is deleted only once neither the live state nor any
 * snapshot in the past or future references it — so undoing a "clear motion" can never point the
 * state at a deleted file. Callers pass the live state to every operation; the history never holds
 * it. Snapshots are cheap: states share their maps, bitmaps and byte arrays.
 *
 * Thread-safe: edits arrive from the UI and from IO coroutines.
 */
internal class EditHistory(
    private val limit: Int = DEFAULT_LIMIT,
    private val clock: () -> Long = System::currentTimeMillis,
    private val delete: (File) -> Unit = { it.delete() },
) {
    private val past = ArrayDeque<StudioState>()
    private val future = ArrayDeque<StudioState>()

    /** Files seen reachable at the last sweep; only these can be deleted (never foreign files). */
    private var tracked: Set<File> = emptySet()
    private var lastKey: String? = null
    private var lastEditAt = 0L

    val canUndo: Boolean @Synchronized get() = past.isNotEmpty()
    val canRedo: Boolean @Synchronized get() = future.isNotEmpty()

    /**
     * Records the edit [before] -> [after]. A non-null [key] equal to the previous edit's, within
     * [COALESCE_GAP_MS], folds into that step (slider drags, typing). Recording clears redo.
     */
    @Synchronized
    fun record(before: StudioState, after: StudioState, key: String?) {
        if (before.document() == after.document()) return
        val now = clock()
        val coalesce = key != null && key == lastKey && now - lastEditAt <= COALESCE_GAP_MS && past.isNotEmpty()
        if (!coalesce) {
            past.addLast(before)
            while (past.size > limit) past.removeFirst()
        }
        future.clear()
        lastKey = key
        lastEditAt = now
        sweep(after)
    }

    /** Steps back from [current]; returns the snapshot to adopt (content only), or null. */
    @Synchronized
    fun undo(current: StudioState): StudioState? {
        val target = past.removeLastOrNull() ?: return null
        future.addLast(current)
        lastKey = null
        sweep(target)
        return target
    }

    /** Steps forward from [current]; returns the snapshot to adopt (content only), or null. */
    @Synchronized
    fun redo(current: StudioState): StudioState? {
        val target = future.removeLastOrNull() ?: return null
        past.addLast(current)
        lastKey = null
        sweep(target)
        return target
    }

    /**
     * Starts a new document (New / Open): forgets all history and deletes every scratch file of
     * [outgoing] and of the history, except those [incoming] already holds.
     */
    @Synchronized
    fun reset(outgoing: StudioState, incoming: StudioState) {
        tracked = tracked + outgoing.scratchFiles()
        past.clear()
        future.clear()
        lastKey = null
        sweep(incoming)
    }

    private fun sweep(live: StudioState) {
        val reachable = buildSet {
            addAll(live.scratchFiles())
            past.forEach { addAll(it.scratchFiles()) }
            future.forEach { addAll(it.scratchFiles()) }
        }
        (tracked - reachable).forEach(delete)
        tracked = reachable
    }

    companion object {
        const val DEFAULT_LIMIT = 100
        const val COALESCE_GAP_MS = 1_000L
    }
}
