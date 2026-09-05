package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.navigation.NavigationDirection
import com.playfieldportal.core.navigation.gridMove

// ── Installed-app picker: pure logic ──────────────────────────────────────────
//
// The picker's selection / search / grid-navigation / diff rules, extracted from XMBViewModel
// so they are unit-testable without a ViewModel or coroutines (same shape as the testable
// predicates shouldShowContextMenuHint / shouldShowAppDrawerHint). XMBViewModel stays the
// owner: it calls these against its `appPicker` state field.
//
// The rules the redesign pins:
//   - toggle changes `selected` ONLY — never focus, never visibility (selection survives any
//     filter, so hiding a checked app cannot silently drop it);
//   - grid moves never wrap off a row edge or leave the grid;
//   - clampFocus returns a valid index for every input, including an empty filtered list;
//   - Apply diffs `selected` against `initialSelected` (membership at open time).

/** Apps the grid shows: label-filtered by [AppPickerState.query] when it is non-blank. */
internal fun AppPickerState.visibleApps(): List<AppPickerEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return apps
    return apps.filter { it.label.lowercase().contains(q) }
}

/** Toggles [pkg]'s membership in `selected`. No-op for a package not in `apps`. */
internal fun AppPickerState.toggle(pkg: String): AppPickerState {
    if (apps.none { it.packageName == pkg }) return this
    return copy(selected = if (pkg in selected) selected - pkg else selected + pkg)
}

/**
 * Grid move over `visibleApps()` using the shared [gridMove] rules — no wrap in any direction,
 * no-op on an empty list. Returns the state unchanged when the move is illegal.
 */
internal fun AppPickerState.move(action: GamepadAction): AppPickerState {
    val visible = visibleApps()
    val direction = when (action) {
        GamepadAction.NAVIGATE_LEFT -> NavigationDirection.LEFT
        GamepadAction.NAVIGATE_RIGHT -> NavigationDirection.RIGHT
        GamepadAction.NAVIGATE_UP -> NavigationDirection.UP
        GamepadAction.NAVIGATE_DOWN -> NavigationDirection.DOWN
        else -> return this
    }
    // Touch mode ends on the first controller input — the cursor reappears where the last
    // touch browse/tap parked it (mirrors AppDrawerViewModel).
    val base = if (usingTouch) copy(usingTouch = false) else this
    val next = gridMove(base.focusedIndex, direction, columns = PICKER_GRID_COLUMNS, size = visible.size)
        ?: return base
    return base.copy(focusedIndex = next)
}

/** Clamps `focusedIndex` into the filtered list's range; 0 for an empty list. */
internal fun AppPickerState.clampFocus(): AppPickerState {
    val lastIndex = visibleApps().lastIndex
    val clamped = focusedIndex.coerceIn(0, lastIndex.coerceAtLeast(0))
    return if (clamped == focusedIndex) this else copy(focusedIndex = clamped)
}

/** Newly-checked packages — what Apply adds. */
internal fun AppPickerState.pendingAdds(): Set<String> = selected - initialSelected

/** Newly-unchecked packages — what Apply removes (after confirmation). */
internal fun AppPickerState.pendingRemovals(): Set<String> = initialSelected - selected

// ── Removal-confirmation modal: hard input boundary ─────────────────────────
//
// While the modal is up the dpad belongs to it: LEFT/RIGHT step between Cancel and Remove
// (no wrap), UP/DOWN are swallowed, and the grid cursor behind the scrim must not move.

/** Raises the modal, cursor parked on Cancel — a fresh prompt never pre-aims at the destructive option. */
internal fun AppPickerState.openConfirm(): AppPickerState =
    copy(confirmingRemovals = true, confirmFocusedOption = AppPickerState.CONFIRM_CANCEL)

/** LEFT/RIGHT step between the two options; every other action (incl. UP/DOWN) is a no-op. */
internal fun AppPickerState.moveConfirm(action: GamepadAction): AppPickerState {
    if (!confirmingRemovals) return this
    val next = when (action) {
        GamepadAction.NAVIGATE_LEFT  -> AppPickerState.CONFIRM_CANCEL
        GamepadAction.NAVIGATE_RIGHT -> AppPickerState.CONFIRM_REMOVE
        else -> return this
    }
    if (next == confirmFocusedOption) return this
    return copy(confirmFocusedOption = next)
}

/** Closes the modal and re-parks the cursor on Cancel for the next prompt. */
internal fun AppPickerState.cancelConfirm(): AppPickerState =
    copy(confirmingRemovals = false, confirmFocusedOption = AppPickerState.CONFIRM_CANCEL)
