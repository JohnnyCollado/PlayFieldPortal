package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameCollection
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.IconDisplayMode
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.core.navigation.NavigationDirection
import com.playfieldportal.core.navigation.gridMove
import com.playfieldportal.core.ui.sound.MenuSound

// ── Game picker ("Add Games to Category"): pure logic ─────────────────────────
//
// The library-shelf picker's rules, kept free of the ViewModel and coroutines so they are
// unit-testable (same shape as AppPickerLogic). The picker is a vertical shelf list beside a
// fixed-column art grid of the current shelf:
//   - "In {category}" first (what the category held when the picker opened), then one shelf per
//     enabled console that has real games, then the custom memory cards that can move in;
//   - two levels, as in the Artwork Studio: the shelf list is a vertical tab list the D-pad steps
//     through, A descends into the grid, B ascends back to the list (and closes from it). LB/RB
//     belong to horizontal tabs, so the picker leaves them alone;
//   - one icon display mode for every tile on screen, whatever each game or console uses in the
//     XMB — X steps it;
//   - selection is keyed by game / collection id, never by tile, so a game shown on both the
//     "In" shelf and its console shelf toggles once;
//   - the header's add/remove counts use the exact diff XMBViewModel.confirmGamePicker applies.

/** Columns in the shelf grid. Layout and navigation both read this, so they cannot drift. */
const val GAME_PICKER_GRID_COLUMNS = 6

/** Which level of the picker holds the controller cursor. */
enum class PickerZone { RAIL, GRID }

/** One shelf in the list. [key] is stable across library updates so per-shelf focus survives. */
sealed interface PickerShelf {
    val key: String
    val title: String
    val size: Int
}

data class GameShelf(
    override val key: String,
    override val title: String,
    val games: List<Game>,
) : PickerShelf {
    override val size: Int get() = games.size
}

data class CardShelf(
    override val key: String,
    override val title: String,
    val cards: List<GameCollection>,
) : PickerShelf {
    override val size: Int get() = cards.size
}

internal const val IN_CATEGORY_SHELF_KEY = "in_category"
internal const val CARDS_SHELF_KEY = "custom_cards"
internal fun platformShelfKey(platformId: String) = "platform_$platformId"

data class GamePickerState(
    val shelves: List<PickerShelf> = emptyList(),
    val shelfIndex: Int = 0,
    // The picker opens on the shelf list, the top level.
    val focusZone: PickerZone = PickerZone.RAIL,
    // Grid index per shelf key: stepping back to a shelf returns to where the cursor last was.
    val focusByShelf: Map<String, Int> = emptyMap(),
    // The one icon display mode every tile is drawn in. Picker-local: never written to settings.
    val viewMode: IconDisplayMode = IconDisplayMode.DEFAULT,
    val selectedGameIds: Set<Long> = emptySet(),
    val selectedCollectionIds: Set<Long> = emptySet(),
    // The category's games when the picker opened — they open checked, and only these can be
    // removed (see XMBViewModel.confirmGamePicker).
    val preselectedGameIds: Set<Long> = emptySet(),
    // True after a tap or drag: the controller cursor is hidden until the next controller input.
    val usingTouch: Boolean = false,
    val isLoading: Boolean = false,
)

/**
 * Builds the shelf list. Only [GameContentType.GAME] entries join (standard apps can't be put in
 * a gaming category), consoles without games are left out, and [movableCollectionIds] (null =
 * every collection) limits the custom memory cards on offer.
 */
internal fun buildShelves(
    cards: List<MemoryCard>,
    games: List<Game>,
    collections: List<GameCollection>,
    preselectedGameIds: Set<Long>,
    movableCollectionIds: Set<Long>?,
    categoryTitle: String,
): List<PickerShelf> {
    val realGames = games.filter { it.contentType == GameContentType.GAME }
    val shelves = mutableListOf<PickerShelf>()

    val inCategory = realGames.filter { it.id in preselectedGameIds }
    if (inCategory.isNotEmpty()) {
        val title = if (categoryTitle.isBlank()) "In This Category" else "In $categoryTitle"
        shelves += GameShelf(IN_CATEGORY_SHELF_KEY, title, inCategory)
    }

    val byPlatform = realGames.groupBy { it.platformId }
    for (card in cards) {
        val platformGames = byPlatform[card.platformId].orEmpty()
        if (platformGames.isNotEmpty()) {
            shelves += GameShelf(platformShelfKey(card.platformId), shelfTitle(card), platformGames)
        }
    }

    val movable = movableCollectionIds?.let { ids -> collections.filter { it.id in ids } } ?: collections
    if (movable.isNotEmpty()) {
        shelves += CardShelf(CARDS_SHELF_KEY, "Custom Memory Cards", movable)
    }
    return shelves
}

// Every console card is "{console} Memory Card"; on a shelf the console is the name, and the
// suffix only costs the narrow list its width.
private const val MEMORY_CARD_SUFFIX = " Memory Card"

internal fun shelfTitle(card: MemoryCard): String =
    card.displayName.removeSuffix(MEMORY_CARD_SUFFIX).ifBlank { card.displayName }

internal fun GamePickerState.currentShelf(): PickerShelf? = shelves.getOrNull(shelfIndex)

/** The grid cursor on the current shelf, always inside its range (0 for an empty shelf). */
internal fun GamePickerState.focusedIndex(): Int {
    val shelf = currentShelf() ?: return 0
    return (focusByShelf[shelf.key] ?: 0).coerceIn(0, (shelf.size - 1).coerceAtLeast(0))
}

/** How many of [shelf]'s items are checked. */
internal fun GamePickerState.checkedCount(shelf: PickerShelf): Int = when (shelf) {
    is GameShelf -> shelf.games.count { it.id in selectedGameIds }
    is CardShelf -> shelf.cards.count { it.id in selectedCollectionIds }
}

/** Games confirm will add, plus the custom memory cards it will move in. */
internal fun GamePickerState.pendingAddCount(): Int =
    (selectedGameIds - preselectedGameIds).size + selectedCollectionIds.size

/** Games confirm will remove: pre-checked at open, unchecked since. */
internal fun GamePickerState.pendingRemovals(): Set<Long> = preselectedGameIds - selectedGameIds

/** Re-clamps the shelf and every remembered focus after the shelves changed under the cursor. */
internal fun GamePickerState.clampFocus(): GamePickerState {
    val index = shelfIndex.coerceIn(0, (shelves.size - 1).coerceAtLeast(0))
    val sizes = shelves.associate { it.key to it.size }
    val focus = focusByShelf
        .filterKeys { it in sizes }
        .mapValues { (key, i) -> i.coerceIn(0, (sizes.getValue(key) - 1).coerceAtLeast(0)) }
    // A shelf emptied under the grid cursor leaves nothing to stand on: back to the list.
    val zone = if (focusZone == PickerZone.GRID && (shelves.getOrNull(index)?.size ?: 0) == 0) PickerZone.RAIL else focusZone
    if (index == shelfIndex && focus == focusByShelf && zone == focusZone) return this
    return copy(shelfIndex = index, focusByShelf = focus, focusZone = zone)
}

private fun GamePickerState.withFocus(index: Int): GamePickerState {
    val shelf = currentShelf() ?: return this
    return copy(focusByShelf = focusByShelf + (shelf.key to index))
}

/**
 * D-pad. On the shelf list, UP/DOWN step through the shelves (the shown shelf follows live) and
 * LEFT/RIGHT do nothing. In the grid, [gridMove] without wrapping. The first controller input
 * after touch only brings the cursor back, where the touch left it.
 */
internal fun GamePickerState.move(action: GamepadAction): GamePickerState {
    val direction = when (action) {
        GamepadAction.NAVIGATE_LEFT -> NavigationDirection.LEFT
        GamepadAction.NAVIGATE_RIGHT -> NavigationDirection.RIGHT
        GamepadAction.NAVIGATE_UP -> NavigationDirection.UP
        GamepadAction.NAVIGATE_DOWN -> NavigationDirection.DOWN
        else -> return this
    }
    if (usingTouch) return copy(usingTouch = false)
    val shelf = currentShelf() ?: return this

    return when (focusZone) {
        PickerZone.RAIL -> when (direction) {
            NavigationDirection.UP -> stepShelf(-1)
            NavigationDirection.DOWN -> stepShelf(+1)
            NavigationDirection.LEFT, NavigationDirection.RIGHT -> this
        }
        PickerZone.GRID ->
            gridMove(focusedIndex(), direction, GAME_PICKER_GRID_COLUMNS, shelf.size)?.let(::withFocus) ?: this
    }
}

/** The previous or next shelf, clamped. */
internal fun GamePickerState.stepShelf(delta: Int): GamePickerState {
    if (shelves.isEmpty()) return this
    val next = (shelfIndex + delta).coerceIn(0, shelves.lastIndex)
    return if (next == shelfIndex) this else copy(shelfIndex = next)
}

/** A: on the shelf list, descends into the grid; in the grid, toggles the focused game or card. */
internal fun GamePickerState.activate(): GamePickerState {
    if (usingTouch) return copy(usingTouch = false)
    if (focusZone == PickerZone.RAIL) {
        return if ((currentShelf()?.size ?: 0) > 0) copy(focusZone = PickerZone.GRID) else this
    }
    return toggleAt(focusedIndex())
}

/** B: from the grid, ascends to the shelf list. Null from the list — B there closes the picker. */
internal fun GamePickerState.back(): GamePickerState? = when (focusZone) {
    PickerZone.GRID -> copy(focusZone = PickerZone.RAIL, usingTouch = false)
    PickerZone.RAIL -> null
}

/** Toggles item [index] on the current shelf. No-op for an index off the shelf. */
internal fun GamePickerState.toggleAt(index: Int): GamePickerState = when (val shelf = currentShelf()) {
    is GameShelf -> shelf.games.getOrNull(index)?.let { game ->
        copy(selectedGameIds = selectedGameIds.toggled(game.id))
    } ?: this
    is CardShelf -> shelf.cards.getOrNull(index)?.let { card ->
        copy(selectedCollectionIds = selectedCollectionIds.toggled(card.id))
    } ?: this
    null -> this
}

/** Y: checks the whole current shelf, or unchecks it when every item is already checked. */
internal fun GamePickerState.toggleWholeShelf(): GamePickerState = when (val shelf = currentShelf()) {
    is GameShelf -> {
        val ids = shelf.games.map { it.id }.toSet()
        val all = ids.isNotEmpty() && selectedGameIds.containsAll(ids)
        copy(selectedGameIds = if (all) selectedGameIds - ids else selectedGameIds + ids)
    }
    is CardShelf -> {
        val ids = shelf.cards.map { it.id }.toSet()
        val all = ids.isNotEmpty() && selectedCollectionIds.containsAll(ids)
        copy(selectedCollectionIds = if (all) selectedCollectionIds - ids else selectedCollectionIds + ids)
    }
    null -> this
}

/** X: the next icon display mode, wrapping — applied to every tile at once. */
internal fun GamePickerState.cycleView(): GamePickerState {
    val modes = IconDisplayMode.entries
    return copy(viewMode = modes[(viewMode.ordinal + 1) % modes.size])
}

/** A tap on tile [index]: focuses and toggles it, and hides the controller cursor. */
internal fun GamePickerState.tapTile(index: Int): GamePickerState =
    withFocus(index).copy(focusZone = PickerZone.GRID, usingTouch = true).toggleAt(index)

/** A tap on shelf [index]: shows that shelf, with the hidden cursor on the list. */
internal fun GamePickerState.tapShelf(index: Int): GamePickerState {
    if (index !in shelves.indices) return this
    return copy(shelfIndex = index, focusZone = PickerZone.RAIL, usingTouch = true)
}

/** A finger scroll settled near tile [index]: the hidden cursor parks there. */
internal fun GamePickerState.touchBrowse(index: Int): GamePickerState {
    val size = currentShelf()?.size ?: return this
    if (index !in 0 until size) return this
    return withFocus(index).copy(focusZone = PickerZone.GRID, usingTouch = true)
}

/**
 * The menu sound for one picker input, from the state before and after it. SCROLL, SELECT and
 * SYSTEM_BROWSE resolve through the Navigation slot the user assigns in Interface ▸ Sound
 * ([MenuSound.slot]); BACK through the Back slot. A check change or a new view is SELECT, a new
 * shelf SYSTEM_BROWSE, descending into the grid SELECT, ascending to the list BACK, and a grid
 * move SCROLL. Inputs that change nothing — a blocked edge, the press that only brings the
 * cursor back after touch — stay silent.
 */
internal fun gamePickerSound(before: GamePickerState, after: GamePickerState): MenuSound? = when {
    before.selectedGameIds != after.selectedGameIds ||
        before.selectedCollectionIds != after.selectedCollectionIds ||
        before.viewMode != after.viewMode -> MenuSound.SELECT
    before.shelfIndex != after.shelfIndex -> MenuSound.SYSTEM_BROWSE
    before.focusZone == PickerZone.RAIL && after.focusZone == PickerZone.GRID -> MenuSound.SELECT
    before.focusZone == PickerZone.GRID && after.focusZone == PickerZone.RAIL -> MenuSound.BACK
    before.focusedIndex() != after.focusedIndex() -> MenuSound.SCROLL
    else -> null
}

private fun Set<Long>.toggled(id: Long): Set<Long> = if (id in this) this - id else this + id
