package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

// ── Shared controller-row registration ──────────────────────────────────────────
//
// The registration plumbing every settings row family shares: a stable FocusRequester, the
// ordered ControllerNavItem entry (with optional inline trailing actions), position/size
// reporting for keep-in-view, and cleanup when the row leaves composition. SettingsRow,
// SettingsFocusable, SettingsTextFieldRow AND the first-run wizard's rows (WizardRow, …) all
// call in here so focus behavior can never drift between the two row families.

// Stable key for rows without an explicit focusKey: derived from the FocusRequester's identity,
// which is remembered and therefore stable for the row's lifetime.
internal fun stableKey(prefix: String, fr: FocusRequester, focusKey: String?): String =
    focusKey ?: "$prefix-${System.identityHashCode(fr)}"

/** Everything a row needs from the registration: focus handle, nav key, geometry reporting. */
internal class ControllerRowRegistration(
    val focusRequester: FocusRequester,
    val rowKey: String,
    // Modifier reporting the row's on-screen Y + height to the scaffold's geometry maps.
    // Insert with .then() right after .focusRequester() in the row's modifier chain.
    val positionReporting: Modifier,
)

/**
 * Registers one controller-navigable row with the surrounding [SettingsScaffold].
 *
 * @param claimInitialFocus rows with a real user action (onClick) claim the screen's
 *   initial-focus slot; read-only rows and action-only directory rows do not, so a screen still
 *   opens on its first ACTION.
 * @param trailingActionsFor builds the row's inline LEFT/RIGHT-reachable actions — invoked with
 *   the row's key (their nav keys are namespaced under it) on every recomposition so the
 *   registered entry stays fresh.
 */
@Composable
internal fun rememberControllerRowRegistration(
    prefix: String,
    focusKey: String?,
    claimInitialFocus: Boolean,
    selectable: Boolean,
    enabled: Boolean = true,
    onSelect: (() -> Unit)?,
    onLongPress: (() -> Unit)? = null,
    trailingActionsFor: (rowKey: String) -> List<ControllerNavItem> = { emptyList() },
): ControllerRowRegistration {
    val focusRegistry = LocalSettingsFocusRegistry.current
    val registerFirst = LocalSettingsRegisterFirstFocusable.current
    val navigationOrder = LocalSettingsNavigationOrder.current
    val rowPositions = LocalSettingsRowPositions.current
    val rowSizes = LocalSettingsRowSizes.current
    val reportRemoved = LocalSettingsReportRemoved.current

    val focusRequester = remember { FocusRequester() }
    if (focusKey != null) {
        DisposableEffect(focusKey) {
            focusRegistry[focusKey] = focusRequester
            onDispose {
                if (focusRegistry[focusKey] === focusRequester) focusRegistry.remove(focusKey)
            }
        }
    }
    val rowKey = stableKey(prefix, focusRequester, focusKey)
    // Built fresh on every recomposition — the SideEffect below keeps the registered entry in
    // lockstep (a toggle row's onSelect flips with its checked state, for example).
    val navItem = ControllerNavItem(
        key = rowKey,
        focusable = true,
        selectable = selectable,
        enabled = enabled,
        onSelect = onSelect,
        onLongPress = onLongPress,
        trailingActions = trailingActionsFor(rowKey),
    )
    DisposableEffect(Unit) {
        navigationOrder?.add(focusRequester to navItem)
        if (claimInitialFocus) registerFirst(focusRequester)
        onDispose {
            navigationOrder?.removeAll { it.first === focusRequester }
            rowPositions?.remove(focusRequester)
            rowSizes?.remove(focusRequester)
            // If this row held focus, the scaffold refocuses the nearest surviving row.
            reportRemoved(focusRequester)
        }
    }
    SideEffect {
        val list = navigationOrder ?: return@SideEffect
        val index = list.indexOfFirst { it.first === focusRequester }
        if (index >= 0) list[index] = focusRequester to navItem
    }
    val positionReporting = if (rowPositions != null && rowSizes != null) {
        val positions = rowPositions
        val sizes = rowSizes
        val req = focusRequester
        Modifier.onGloballyPositioned {
            positions[req] = it.localToRoot(Offset.Zero).y
            sizes[req] = it.size.height.toFloat()
        }
    } else {
        Modifier
    }
    return ControllerRowRegistration(focusRequester, rowKey, positionReporting)
}

/**
 * One controller-reachable inline action (the Edit/Remove pair on a root row), rendered in a
 * row's trailing slot and registered for LEFT/RIGHT traversal. Reached by pressing RIGHT onto
 * the row; SELECT activates the focused action. Shared by [SettingsRow] and the wizard's
 * WizardRootRow so both render and navigate identically.
 */
@Composable
internal fun SettingsRowActionButton(
    rowKey: String,
    index: Int,
    action: SettingsRowAction,
    onFocusedChanged: (Boolean) -> Unit,
) {
    val focusTracker = LocalSettingsFocusTracker.current
    val touchInput = LocalSettingsTouchInput.current
    val rowActionFrs = LocalSettingsRowActions.current
    val reportFocused = LocalSettingsReportFocused.current
    val reportRemoved = LocalSettingsReportRemoved.current
    val actionFr = remember { FocusRequester() }
    val actionKey = "$rowKey:action:$index"
    var actionFocused by remember { mutableStateOf(false) }
    DisposableEffect(actionKey) {
        val list = rowActionFrs?.getOrPut(rowKey) { mutableStateListOf() }
        list?.add(actionKey to actionFr)
        onDispose {
            list?.removeAll { it.second === actionFr }
            reportRemoved(actionFr)
        }
    }
    IconButton(
        onClick = action.onClick,
        modifier = Modifier
            .pointerInput(actionKey, action.onClick, action.onLongPress) {
                detectTapGestures(
                    onTap = { touchInput(); action.onClick() },
                    onLongPress = { touchInput(); action.onLongPress?.invoke() },
                )
            }
            .focusRequester(actionFr)
            .onFocusChanged { state ->
                actionFocused = state.isFocused
                onFocusedChanged(state.isFocused)
                if (state.isFocused) {
                    focusTracker(action.onClick)
                    reportFocused(actionFr)
                }
            }
            .focusable(),
    ) {
        Box(
            modifier = Modifier
                .background(
                    if (actionFocused) action.actionFocusBackgroundColor else Color.Transparent,
                    RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            action.icon()
        }
    }
}
