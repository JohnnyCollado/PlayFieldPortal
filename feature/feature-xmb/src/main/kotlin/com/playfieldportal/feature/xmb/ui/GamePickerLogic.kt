package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameCollection
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.core.domain.model.IconDisplayMode
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.core.domain.model.TouchGesture
import com.playfieldportal.core.navigation.packFlowRows
import com.playfieldportal.core.ui.components.PspMenuRow
import com.playfieldportal.core.ui.components.TouchPromptItem
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.feature.artwork.store.ArtworkDimensions

// ── Game picker ("Add Games to Category"): pure logic ─────────────────────────
//
// The library-shelf picker's rules, kept free of the ViewModel and coroutines so they are
// unit-testable (same shape as AppPickerLogic). The picker is a vertical shelf list beside a
// free-flowing art grid of the current shelf, each tile at its XMB size:
//   - "In {category}" first (what the category held when the picker opened), then one shelf per
//     enabled console that has real games, then the custom memory cards that can move in;
//   - two levels, as in the Artwork Studio: the shelf list is a vertical tab list the D-pad steps
//     through, A descends into the grid, B ascends back to the list (and closes from it). LB/RB
//     belong to horizontal tabs, so the picker leaves them alone. The cursor itself runs on the
//     unified navigation engine — see GamePickerNav;
//   - one icon display mode for every tile on screen, whatever each game or console uses in the
//     XMB — X steps it;
//   - selection is keyed by game / collection id, never by tile, so a game shown on both the
//     "In" shelf and its console shelf toggles once;
//   - the header's add/remove counts use the exact diff XMBViewModel.confirmGamePicker applies.

// ── Shelf layout: XMB-sized tiles in free-flowing rows ────────────────────────
//
// Every tile is drawn at the size it has in the XMB, and rows pack as many as the pane fits, so
// an ICON0 shelf reads at full size and a shelf of narrow UMD cases fits many to a row. Layout
// and navigation both read [shelfRows], so they cannot drift. Units are dp.

/** ICON0 at the XMB game row's size (XMBItemList's GAME_ICON_WIDTH × GAME_ICON_HEIGHT). */
internal const val PICKER_ICON0_WIDTH_DP = 126f
internal const val PICKER_ICON0_HEIGHT_DP = 70f
/** Box Art / 3D Box / Physical Media height — the XMB's NATURAL_ART_HEIGHT. */
internal const val PICKER_ART_HEIGHT_DP = 84f
/** Chrome room around the art: the focus frame sits this far outside it on every side. */
internal const val PICKER_FRAME_PAD_DP = 4f
internal const val PICKER_TILE_SPACING_DP = 8f
/** The narrowest tile: below this the two-line label stops reading. A UMD case lands here. */
internal const val PICKER_MIN_TILE_WIDTH_DP = 64f
// Cartridge and disc shots have no per-platform preset; a square frame suits both.
internal const val PICKER_PHYSICAL_MEDIA_ASPECT = 1f

/** The art's width for a game on [platformId] in [view], at its XMB size. */
internal fun pickerArtWidthDp(platformId: String?, view: IconDisplayMode): Float = when (view) {
    IconDisplayMode.ICON0 -> PICKER_ICON0_WIDTH_DP
    IconDisplayMode.BOX_ART, IconDisplayMode.BOX_3D -> PICKER_ART_HEIGHT_DP * ArtworkDimensions.boxArt(platformId).aspectRatio
    IconDisplayMode.PHYSICAL_MEDIA -> PICKER_ART_HEIGHT_DP * PICKER_PHYSICAL_MEDIA_ASPECT
}

/** A whole tile's width: the art plus its frame room, never under [PICKER_MIN_TILE_WIDTH_DP]. */
internal fun pickerTileWidthDp(platformId: String?, view: IconDisplayMode): Float =
    (pickerArtWidthDp(platformId, view) + PICKER_FRAME_PAD_DP * 2).coerceAtLeast(PICKER_MIN_TILE_WIDTH_DP)

/** Each tile's width on this shelf. Custom memory cards are landscape, ICON0-wide. */
internal fun PickerShelf.tileWidthsDp(view: IconDisplayMode): List<Float> = when (this) {
    is GameShelf -> games.map { pickerTileWidthDp(it.platformId, view) }
    is CardShelf -> List(cards.size) { PICKER_ICON0_WIDTH_DP + PICKER_FRAME_PAD_DP * 2 }
}

/** The current shelf's rows in the current view, at the measured pane width (the core's FlowGrid packing). */
internal fun GamePickerState.shelfRows(): List<IntRange> {
    val shelf = currentShelf() ?: return emptyList()
    return packFlowRows(shelf.tileWidthsDp(viewMode), shelfWidthDp, PICKER_TILE_SPACING_DP)
}

/** The row holding tile [index], or -1. */
internal fun List<IntRange>.rowOf(index: Int): Int = indexOfFirst { index in it }

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
    // The shelf pane's measured width in dp: rows pack to it. 0 until the pane first lays out.
    val shelfWidthDp: Float = 0f,
    val selectedGameIds: Set<Long> = emptySet(),
    val selectedCollectionIds: Set<Long> = emptySet(),
    // The category's games when the picker opened — they open checked, and only these can be
    // removed (see XMBViewModel.confirmGamePicker).
    val preselectedGameIds: Set<Long> = emptySet(),
    // True after a tap or drag: the controller cursor is hidden until the next controller input.
    val usingTouch: Boolean = false,
    val isLoading: Boolean = false,
    // Y's options menu (View ›, Select All); null while closed.
    val menu: PickerMenu? = null,
    // X's search of the current shelf: the query narrows its tiles to matching titles.
    val searchActive: Boolean = false,
    val query: String = "",
    // Bumped by X on an open search with text: brings the keyboard back without wiping the text.
    val searchReopens: Int = 0,
)

/** Which list Y's menu shows: its root, or the View modes beneath it. */
enum class PickerMenuLevel { ROOT, VIEW }

data class PickerMenu(val level: PickerMenuLevel = PickerMenuLevel.ROOT, val selectedIndex: Int = 0)

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

/**
 * The shelf the grid shows. While a search has a query it is that shelf narrowed to matching
 * titles (any case), so the rows, the cursor, taps and Select All all work on what is on screen;
 * the shelf list keeps counting the whole shelf.
 */
internal fun GamePickerState.currentShelf(): PickerShelf? {
    val shelf = shelves.getOrNull(shelfIndex) ?: return null
    val q = query.trim()
    if (!searchActive || q.isEmpty()) return shelf
    return when (shelf) {
        is GameShelf -> shelf.copy(games = shelf.games.filter { it.displayTitle.contains(q, ignoreCase = true) })
        is CardShelf -> shelf.copy(cards = shelf.cards.filter { it.name.contains(q, ignoreCase = true) })
    }
}

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

/** The header's pending-change line: only the parts that are not zero, or "No changes". */
internal fun pendingChangeLabel(adds: Int, removals: Int): String =
    listOfNotNull(
        adds.takeIf { it > 0 }?.let { "$it to add" },
        removals.takeIf { it > 0 }?.let { "$it to remove" },
    ).joinToString(" · ").ifEmpty { "No changes" }

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

/**
 * How far to scroll a list so the item spanning [itemStart]..[itemEnd] sits wholly inside the
 * viewport [viewStart]..[viewEnd] (all in px along the list): negative scrolls back, positive
 * forward, 0 when it is already whole. Only as far as needed, so stepping down never jumps the
 * cursor to the top; an item taller than the viewport lines its top up. A sliver on screen still
 * counts as off screen — that is what left the shelf cursor hidden under the header.
 */
internal fun scrollIntoViewDelta(itemStart: Int, itemEnd: Int, viewStart: Int, viewEnd: Int): Int = when {
    itemStart < viewStart -> itemStart - viewStart
    itemEnd > viewEnd -> minOf(itemEnd - viewEnd, itemStart - viewStart)
    else -> 0
}

// ── Y: the options menu ───────────────────────────────────────────────────────
//
// The shared PSP panel and its rules (PspMenuNav): View › opens the modes, Select All toggles the
// shelf on screen. Picking either closes the menu.

/** Y: opens the menu at its root, on View. */
internal fun GamePickerState.openMenu(): GamePickerState = copy(menu = PickerMenu())

internal fun GamePickerState.closeMenu(): GamePickerState = copy(menu = null)

/** The rows of the open menu's level; empty while it is closed. */
internal fun GamePickerState.menuRows(): List<PspMenuRow> = when (menu?.level) {
    null -> emptyList()
    PickerMenuLevel.ROOT -> listOf(
        PspMenuRow("View", value = viewMode.label, opensMenu = true),
        PspMenuRow("Select All", value = if (wholeShelfChecked()) "On" else "Off"),
    )
    PickerMenuLevel.VIEW -> IconDisplayMode.entries.map { PspMenuRow(it.label, checked = it == viewMode) }
}

/** A on the focused menu row: View descends to the modes; a mode applies and closes; Select All toggles and closes. */
internal fun GamePickerState.activateMenuRow(): GamePickerState {
    val open = menu ?: return this
    return when (open.level) {
        PickerMenuLevel.ROOT -> when (open.selectedIndex) {
            0 -> copy(menu = PickerMenu(PickerMenuLevel.VIEW, selectedIndex = viewMode.ordinal))
            else -> toggleWholeShelf().closeMenu()
        }
        PickerMenuLevel.VIEW ->
            IconDisplayMode.entries.getOrNull(open.selectedIndex)?.let { copy(viewMode = it, menu = null) } ?: this
    }
}

/** B below the root: back up to it, on View. */
internal fun GamePickerState.menuUp(): GamePickerState = copy(menu = PickerMenu(PickerMenuLevel.ROOT, selectedIndex = 0))

// Every tile on screen checked: what Select All's On reads.
private fun GamePickerState.wholeShelfChecked(): Boolean = when (val shelf = currentShelf()) {
    is GameShelf -> shelf.games.isNotEmpty() && shelf.games.all { it.id in selectedGameIds }
    is CardShelf -> shelf.cards.isNotEmpty() && shelf.cards.all { it.id in selectedCollectionIds }
    null -> false
}

// ── X: search the current shelf ───────────────────────────────────────────────

/**
 * X. A closed search opens; an open one that still holds text brings PFP's keyboard back for more
 * typing; an open, empty one closes. The same rule as the App Picker's search.
 */
internal fun GamePickerState.pressSearch(): GamePickerState = when {
    !searchActive -> copy(searchActive = true)
    query.isNotBlank() -> copy(searchReopens = searchReopens + 1)
    else -> closeSearch()
}

internal fun GamePickerState.closeSearch(): GamePickerState = copy(searchActive = false, query = "")

// ── Touch mode ────────────────────────────────────────────────────────────────
//
// One input family on screen at a time (ARCHITECTURE.md ▸ Conventions). A finger cannot press X,
// Y or HOME, so in touch mode Search, Done and Options become header pills (the ◀ breadcrumb
// backs out, as B does) and the footer names the tap: a shelf tap opens it, a tile tap toggles it.

internal fun gamePickerTouchPrompts(): List<TouchPromptItem> =
    listOf(TouchPromptItem(TouchGesture.TAP, "Open / Toggle"))

/** A touch-mode header pill, in header order. */
internal enum class GamePickerPill { SEARCH, DONE, OPTIONS }

/**
 * The header's touch pills: none outside touch mode or while the Options menu is over the screen.
 * Search steps aside once open — the field takes the taps and the ◀ breadcrumb closes it.
 */
internal fun gamePickerTouchPills(state: GamePickerState, showTouchControls: Boolean): List<GamePickerPill> = when {
    !showTouchControls || state.menu != null -> emptyList()
    state.searchActive -> listOf(GamePickerPill.DONE, GamePickerPill.OPTIONS)
    else -> listOf(GamePickerPill.SEARCH, GamePickerPill.DONE, GamePickerPill.OPTIONS)
}
