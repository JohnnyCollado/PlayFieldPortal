package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.navigation.FlowCell
import com.playfieldportal.core.navigation.FlowGrid
import com.playfieldportal.core.navigation.NavigationCommand
import com.playfieldportal.core.navigation.NavigationDirection
import com.playfieldportal.core.navigation.NavigationEngine
import com.playfieldportal.core.navigation.NavigationLogger
import com.playfieldportal.core.navigation.NavigationNode

// ── Game picker navigation (unified engine adapter) ───────────────────────────
//
// The picker's cursor runs on the shared core-navigation engine, as Game Detail and the music
// browser do: the shelf list is the root context (a list), and descending into a shelf pushes a
// nested context laid out as that shelf's FlowGrid — the same rows the screen draws. Every element
// is a stable semantic node (a shelf key, a game or card id), never a position.
//
// [GamePickerState] stays the render model: each call lays the engine graph out from the state,
// lets the engine apply its rules (movement, edges, levels, touch hiding the cursor), and writes
// the focus back. The adapter never touches Compose or a repository, so the whole contract is
// unit-testable on the JVM.

/** Stable semantic keys for the game picker. Never derived from positions. */
internal object GamePickerKeys {
    const val SHELVES = "game-picker:shelves"
    const val GRID = "game-picker:grid"

    fun shelf(key: String): String = "game-picker:shelf:$key"

    fun tile(shelf: PickerShelf, index: Int): String = when (shelf) {
        is GameShelf -> "game-picker:game:${shelf.games[index].id}"
        is CardShelf -> "game-picker:card:${shelf.cards[index].id}"
    }
}

internal class GamePickerNav(logger: NavigationLogger = NavigationLogger.NONE) {

    private val engine = NavigationEngine(GamePickerKeys.SHELVES, logger).apply { markReady() }

    // Set by a node's action while a command is dispatched, read straight after it.
    private var descended = false
    private var toggledIndex: Int? = null
    private var climbed = false

    /**
     * D-pad. On the shelf list UP/DOWN step the shelves (the shown shelf follows) and LEFT/RIGHT do
     * nothing; in a shelf, the FlowGrid. The first controller input after touch only brings the
     * cursor back, where the touch left it.
     */
    fun move(state: GamePickerState, action: GamepadAction): GamePickerState {
        val direction = action.direction() ?: return state
        if (state.usingTouch) return state.copy(usingTouch = false)
        if (state.currentShelf() == null) return state
        layOut(state)
        engine.dispatch(NavigationCommand.Direction(direction))
        return readBack(state)
    }

    /** A: on the shelf list, descends into the shelf (an empty one stays put); in a shelf, toggles the focused tile. */
    fun activate(state: GamePickerState): GamePickerState {
        if (state.usingTouch) return state.copy(usingTouch = false)
        if (state.currentShelf() == null) return state
        layOut(state)
        descended = false
        toggledIndex = null
        engine.dispatch(NavigationCommand.Confirm)
        return when {
            descended -> state.copy(focusZone = PickerZone.GRID)
            else -> toggledIndex?.let { readBack(state).toggleAt(it) } ?: state
        }
    }

    /** B: from a shelf, back up to the shelf list. Null from the list — B there closes the picker. */
    fun back(state: GamePickerState): GamePickerState? {
        if (state.focusZone == PickerZone.RAIL) return null
        layOut(state)
        climbed = false
        engine.backHandler = { climbed = engine.popContext() != null }
        engine.dispatch(NavigationCommand.Back)
        return if (climbed) state.copy(focusZone = PickerZone.RAIL, usingTouch = false) else state
    }

    /** A tap on tile [index]: focuses and toggles it, and hides the controller cursor. */
    fun tapTile(state: GamePickerState, index: Int): GamePickerState {
        val shelf = state.currentShelf() ?: return state
        if (index !in 0 until shelf.size) return state
        val inGrid = state.copy(focusZone = PickerZone.GRID)
        layOut(inGrid)
        toggledIndex = null
        engine.dispatchTouch(GamePickerKeys.tile(shelf, index), com.playfieldportal.core.navigation.NavigationTouchAction.TAP)
        return readBack(inGrid).let { s -> toggledIndex?.let { s.toggleAt(it) } ?: s }
    }

    /** A tap on shelf [index]: shows that shelf, with the hidden cursor on the list. Never descends. */
    fun tapShelf(state: GamePickerState, index: Int): GamePickerState {
        val shelf = state.shelves.getOrNull(index) ?: return state
        val onList = state.copy(focusZone = PickerZone.RAIL)
        layOut(onList)
        engine.setFocused(GamePickerKeys.shelf(shelf.key))
        engine.markTouchInput()
        return readBack(onList)
    }

    /** A finger scroll settled near tile [index]: the hidden cursor parks there. */
    fun touchBrowse(state: GamePickerState, index: Int): GamePickerState {
        val shelf = state.currentShelf() ?: return state
        if (index !in 0 until shelf.size) return state
        val inGrid = state.copy(focusZone = PickerZone.GRID)
        layOut(inGrid)
        engine.setFocused(GamePickerKeys.tile(shelf, index))
        engine.markTouchInput()
        return readBack(inGrid)
    }

    // Lays the engine graph out from [state]: the shelf list, and — in a shelf — its grid on top.
    private fun layOut(state: GamePickerState) {
        while (engine.activeContextId != GamePickerKeys.SHELVES) engine.popContext()
        engine.replaceNodes(state.shelves.map { shelf ->
            NavigationNode(GamePickerKeys.shelf(shelf.key), onSelect = { if (shelf.size > 0) descended = true })
        })
        val shelf = state.currentShelf() ?: return
        engine.setFocused(GamePickerKeys.shelf(shelf.key))
        if (state.focusZone == PickerZone.GRID && shelf.size > 0) {
            engine.pushContext(GamePickerKeys.GRID)
            val widths = shelf.tileWidthsDp(state.viewMode)
            val grid = FlowGrid(
                rows = state.shelfRows().map { row -> row.map { i -> FlowCell(GamePickerKeys.tile(shelf, i), widths[i]) } },
                spacing = PICKER_TILE_SPACING_DP,
            )
            engine.replaceFlowGrid(grid, grid.keys.mapIndexed { i, key -> NavigationNode(key, onSelect = { toggledIndex = i }) })
            engine.setFocused(GamePickerKeys.tile(shelf, state.focusedIndex()))
        }
        if (state.usingTouch) engine.markTouchInput() else engine.markControllerInput()
    }

    // Writes the engine's focus back into [state]: the shelf on the list, or the tile in a shelf.
    private fun readBack(state: GamePickerState): GamePickerState {
        val key = engine.focusedKey
        val touch = !engine.cursorVisible
        if (engine.activeContextId == GamePickerKeys.SHELVES) {
            val index = state.shelves.indexOfFirst { GamePickerKeys.shelf(it.key) == key }.takeIf { it >= 0 } ?: state.shelfIndex
            // A search belongs to the shelf it was made on: another shelf starts unfiltered.
            val base = if (index != state.shelfIndex) state.closeSearch() else state
            return base.copy(shelfIndex = index, usingTouch = touch)
        }
        val shelf = state.currentShelf() ?: return state
        val index = (0 until shelf.size).firstOrNull { GamePickerKeys.tile(shelf, it) == key } ?: return state
        return state.copy(
            focusZone = PickerZone.GRID,
            focusByShelf = state.focusByShelf + (shelf.key to index),
            usingTouch = touch,
        )
    }

    private fun GamepadAction.direction(): NavigationDirection? = when (this) {
        GamepadAction.NAVIGATE_LEFT -> NavigationDirection.LEFT
        GamepadAction.NAVIGATE_RIGHT -> NavigationDirection.RIGHT
        GamepadAction.NAVIGATE_UP -> NavigationDirection.UP
        GamepadAction.NAVIGATE_DOWN -> NavigationDirection.DOWN
        else -> null
    }
}
