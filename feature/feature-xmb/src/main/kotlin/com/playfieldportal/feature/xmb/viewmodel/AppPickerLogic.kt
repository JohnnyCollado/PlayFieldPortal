package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.TouchGesture
import com.playfieldportal.core.ui.components.TouchPromptItem
import com.playfieldportal.core.ui.sound.MenuSound

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
//   - the cursor itself (grid moves, the confirmation's two options) runs on the unified
//     navigation engine — see AppPickerNav;
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

/** Clamps `focusedIndex` into the filtered list's range; 0 for an empty list. */
internal fun AppPickerState.clampFocus(): AppPickerState {
    val lastIndex = visibleApps().lastIndex
    val clamped = focusedIndex.coerceIn(0, lastIndex.coerceAtLeast(0))
    return if (clamped == focusedIndex) this else copy(focusedIndex = clamped)
}

/** Newly-checked packages — what Apply adds. */
internal fun AppPickerState.pendingAdds(): Set<String> = selected - initialSelected

/**
 * X, the controller's search button. A closed search opens; an open one that still holds text
 * brings PFP's keyboard back for more typing rather than wiping it (the keyboard's Done leaves the
 * search open with the keyboard down); an open, empty one closes.
 */
internal fun AppPickerState.pressSearch(): AppPickerState = when {
    !searchActive -> copy(searchActive = true)
    query.isNotBlank() -> copy(searchReopens = searchReopens + 1)
    else -> copy(searchActive = false, query = "")
}

/** Newly-unchecked packages — what Apply removes (after confirmation). */
internal fun AppPickerState.pendingRemovals(): Set<String> = initialSelected - selected

// ── Removal-confirmation modal: hard input boundary ─────────────────────────
//
// While the modal is up the dpad belongs to it (a modal context in AppPickerNav): LEFT/RIGHT step
// between Cancel and Remove (no wrap), UP/DOWN go nowhere, and the grid behind the scrim is paused.

/** Raises the modal, cursor parked on Cancel — a fresh prompt never pre-aims at the destructive option. */
internal fun AppPickerState.openConfirm(): AppPickerState =
    copy(confirmingRemovals = true, confirmFocusedOption = AppPickerState.CONFIRM_CANCEL)

/** Closes the modal and re-parks the cursor on Cancel for the next prompt. */
internal fun AppPickerState.cancelConfirm(): AppPickerState =
    copy(confirmingRemovals = false, confirmFocusedOption = AppPickerState.CONFIRM_CANCEL)

/**
 * The menu sound for one picker input, from the state before and after it. Both resolve through
 * the Navigation slot the user assigns in Interface ▸ Sound ([MenuSound.slot]). A toggle reads as
 * SELECT, a cursor that actually moved (grid or modal option) as SCROLL; an input that changed
 * nothing — a blocked edge, an empty grid — is silent.
 */
internal fun appPickerSound(before: AppPickerState?, after: AppPickerState?): MenuSound? = when {
    before == null || after == null -> null
    before.selected != after.selected -> MenuSound.SELECT
    before.focusedIndex != after.focusedIndex -> MenuSound.SCROLL
    before.confirmingRemovals && before.confirmFocusedOption != after.confirmFocusedOption -> MenuSound.SCROLL
    else -> null
}

/** The removal confirmation's question: apps leave a custom card, or the library they were picked into. */
internal fun removalQuestion(target: AppPickerTarget, count: Int): String {
    val place = if (target is AppPickerTarget.CardApps) "card" else "library"
    return "Remove $count app(s) from this $place?"
}

// ── Touch mode ────────────────────────────────────────────────────────────────
//
// One input family on screen at a time (ARCHITECTURE.md ▸ Conventions). A finger cannot press HOME,
// so in touch mode Search and ✓ Done become header pills (the ◀ breadcrumb backs out) and the footer names the tap
// the grid binds.

/** The touch footer. Empty while the removal panel is up — its Cancel / Remove buttons say it all. */
internal fun appPickerTouchPrompts(confirmingRemovals: Boolean): List<TouchPromptItem> =
    if (confirmingRemovals) emptyList() else listOf(TouchPromptItem(TouchGesture.TAP, "Toggle"))

/** Whether the header carries the Search / Done pills: touch mode, and no modal over the grid. */
internal fun showAppPickerTouchPills(state: AppPickerState, showTouchControls: Boolean): Boolean =
    showTouchControls && !state.confirmingRemovals
