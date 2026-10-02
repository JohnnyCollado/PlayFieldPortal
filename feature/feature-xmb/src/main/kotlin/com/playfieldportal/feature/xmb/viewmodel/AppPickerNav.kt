package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.navigation.FlowCell
import com.playfieldportal.core.navigation.FlowGrid
import com.playfieldportal.core.navigation.NavigationCommand
import com.playfieldportal.core.navigation.NavigationDirection
import com.playfieldportal.core.navigation.NavigationEngine
import com.playfieldportal.core.navigation.NavigationLogger
import com.playfieldportal.core.navigation.NavigationNode

// ── Installed-app picker navigation (unified engine adapter) ──────────────────
//
// The picker's cursor runs on the shared core-navigation engine: the app grid is a context laid
// out as a FlowGrid of equal tiles (PICKER_GRID_COLUMNS to a row), and the removal confirmation is
// a modal context on top of it — Cancel and Remove in one row — so while it is up the D-pad can
// only reach those two, by the engine's own modal rule. Every node is keyed by package name.
//
// [AppPickerState] stays the render model (selection, search, the confirm's flags): each call lays
// the graph out from it, lets the engine move, and writes the focus back. Pure, JVM-testable.

internal object AppPickerKeys {
    const val GRID = "app-picker:grid"
    const val CONFIRM = "app-picker:modal:confirm"
    const val CONFIRM_CANCEL = "app-picker:confirm:cancel"
    const val CONFIRM_REMOVE = "app-picker:confirm:remove"

    fun app(packageName: String): String = "app-picker:app:$packageName"
}

internal class AppPickerNav(logger: NavigationLogger = NavigationLogger.NONE) {

    private val engine = NavigationEngine(AppPickerKeys.GRID, logger).apply { markReady() }

    /**
     * D-pad. In the grid, the FlowGrid — no wrap; the first input after touch only brings the cursor
     * back. While the removal confirmation is up, LEFT/RIGHT step Cancel ⇄ Remove and nothing else moves.
     */
    fun move(state: AppPickerState, action: GamepadAction): AppPickerState {
        val direction = action.direction() ?: return state
        if (state.confirmingRemovals) {
            layOut(state)
            engine.dispatch(NavigationCommand.Direction(direction))
            val option = if (engine.focusedKey == AppPickerKeys.CONFIRM_REMOVE) AppPickerState.CONFIRM_REMOVE else AppPickerState.CONFIRM_CANCEL
            return if (option == state.confirmFocusedOption) state else state.copy(confirmFocusedOption = option)
        }
        if (state.usingTouch) return state.copy(usingTouch = false)
        val visible = state.visibleApps()
        if (visible.isEmpty()) return state
        layOut(state)
        engine.dispatch(NavigationCommand.Direction(direction))
        val index = visible.indexOfFirst { AppPickerKeys.app(it.packageName) == engine.focusedKey }
        return if (index < 0 || index == state.focusedIndex) state else state.copy(focusedIndex = index)
    }

    // Lays the graph out from [state]: the grid of visible apps, and the confirmation over it.
    private fun layOut(state: AppPickerState) {
        while (engine.activeContextId != AppPickerKeys.GRID) engine.popContext()
        val visible = state.visibleApps()
        val grid = FlowGrid.pack(visible.map { FlowCell(AppPickerKeys.app(it.packageName), 1f) }, available = PICKER_GRID_COLUMNS.toFloat(), spacing = 0f)
        engine.replaceFlowGrid(grid, grid.keys.map { NavigationNode(it) })
        visible.getOrNull(state.focusedIndex)?.let { engine.setFocused(AppPickerKeys.app(it.packageName)) }
        if (state.confirmingRemovals) {
            engine.pushModal(AppPickerKeys.CONFIRM)
            val options = FlowGrid(listOf(listOf(FlowCell(AppPickerKeys.CONFIRM_CANCEL, 1f), FlowCell(AppPickerKeys.CONFIRM_REMOVE, 1f))))
            engine.replaceFlowGrid(options, options.keys.map { NavigationNode(it) })
            engine.setFocused(
                if (state.confirmFocusedOption == AppPickerState.CONFIRM_REMOVE) AppPickerKeys.CONFIRM_REMOVE else AppPickerKeys.CONFIRM_CANCEL,
            )
        }
    }

    private fun GamepadAction.direction(): NavigationDirection? = when (this) {
        GamepadAction.NAVIGATE_LEFT -> NavigationDirection.LEFT
        GamepadAction.NAVIGATE_RIGHT -> NavigationDirection.RIGHT
        GamepadAction.NAVIGATE_UP -> NavigationDirection.UP
        GamepadAction.NAVIGATE_DOWN -> NavigationDirection.DOWN
        else -> null
    }
}
