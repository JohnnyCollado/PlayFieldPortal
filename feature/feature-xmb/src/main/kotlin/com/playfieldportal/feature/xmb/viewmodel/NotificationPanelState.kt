package com.playfieldportal.feature.xmb.viewmodel


/**
 * The notification panel, open. Null on [XMBUiState] means closed.
 *
 * Only the cursor lives here: the two lists it draws are already on [XMBUiState]
 * ([XMBUiState.runningTasks] in memory, [XMBUiState.notifications] from Room), so opening the
 * panel never snapshots them and a task that settles while it is open updates the panel in place.
 */
data class NotificationPanelState(
    /** Index into the built row list, or -1 when nothing in the panel is actionable. */
    val cursor: Int = -1,
)

/** What a START press means once the pickers and the context menu have had their turn. */
enum class StartOutcome {
    /** The panel is open: START is the way back out, exactly as it was the way in. */
    CLOSE_PANEL,

    /** Plain XMB. START opens the panel — the one line that replaced a dead `-> Unit`. */
    OPEN_PANEL,

    /**
     * Something else is layered over the XMB. START belongs to that overlay's own branch, which is
     * what keeps START = Confirm inside the app / game / track pickers and the Artwork Studio.
     */
    FORWARD_TO_OVERLAY,
}

/**
 * The single rule behind START, so the panel's open path and its close path cannot drift.
 *
 * The dispatcher consults this *below* the picker and context-menu branches — those consume START
 * and return before it is reached — which is why a picker state here reads as
 * [StartOutcome.FORWARD_TO_OVERLAY] rather than as a special case.
 */
fun startOutcome(state: XMBUiState): StartOutcome = when {
    state.notificationPanel != null -> StartOutcome.CLOSE_PANEL
    state.hasBlockingOverlay -> StartOutcome.FORWARD_TO_OVERLAY
    else -> StartOutcome.OPEN_PANEL
}

/**
 * Rows the Options menu offers. Ids are shared with the context-menu code.
 *
 * The menu acts on the list, never on one row. A row has a single meaning — open it — and Confirm
 * already carries that, so a per-row menu would be a submenu holding one real entry beside two
 * different ways to say "read", for a list most people will clear wholesale anyway.
 */
object NotificationMenuIds {
    const val MARK_ALL_READ = "notif_mark_all_read"
    const val CLEAR_READ = "notif_clear_read"
    const val CLEAR_ALL = "notif_clear_all"
}
