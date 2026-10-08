package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.ListArrangement
import com.playfieldportal.core.domain.model.ListKeys
import com.playfieldportal.core.domain.model.ListSortMode
import com.playfieldportal.core.domain.model.ListState
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.feature.appbar.CategorizedApp
import com.playfieldportal.feature.xmb.ui.shelfTitle

// ── How the XMB arranges a list ───────────────────────────────────────────────
//
// Every arrangeable list on the XMB has a key (see ListKeys) under which its sort override, its
// Custom order and its pinned games are stored. This file is the pure half of that: which list is
// on screen, which sort applies to it, where each row lands, and what a Move may do. XMBViewModel
// owns the state and the writes.

/** Sentinel `selectedPlatformId` while a custom gaming category's own Memory Card is open. */
internal const val CATEGORY_CARD_PLATFORM_ID = "__category_card__"

/** What a list holds, which decides the sorts it offers. */
enum class XmbListKind {
    /** Game rows — a Memory Card, All Games, Favorites, a custom memory card. */
    GAMES,
    /** App rows — an app column or a media section's apps. */
    APPS,
    /** A gaming column's top level — cards and custom cards, in default or Custom order. */
    ROOT,
}

/**
 * One row being moved. [originalItems] and [originalIndex] are what Cancel restores; the list on
 * screen is reordered live as the row moves.
 */
data class MoveSession(
    val listKey: String,
    val originalItems: List<XMBItem>,
    val originalIndex: Int,
)

/**
 * A category lifted on the crossbar (Settings ▸ Category Manager ▸ Move). Left / right slide it
 * live; [originalCategories] and [originalIndex] are what Cancel puts back.
 */
data class CategoryMoveSession(
    val originalCategories: List<Category>,
    val originalIndex: Int,
    // Set when the move began in Category Manager: the bar column it was opened from, which
    // ending the move returns to, along with the manager itself.
    val returnToCategoryId: String? = null,
)

/** Settings ▸ Categories — the Category Manager screen. */
internal const val CATEGORY_MANAGER_SCREEN_ID = "settings_categories"

/**
 * Where ending this move (placed or cancelled) returns: the bar index of the column Category
 * Manager was opened from (null if it has left the bar — the moved category stays selected) and
 * the manager screen. Null
 * when the move began on the crossbar itself, which stays where it is.
 */
internal fun CategoryMoveSession.returnAfterMove(categories: List<Category>): Pair<Int?, String>? {
    val returnTo = returnToCategoryId ?: return null
    return categories.indexOfFirst { it.id == returnTo }.takeIf { it >= 0 } to CATEGORY_MANAGER_SCREEN_ID
}

/**
 * [categories] with the one at [index] slid one slot by [delta], and the index it lands on. Null
 * off either end of the bar. Every category on the bar may move, Settings included.
 */
internal fun moveCategory(categories: List<Category>, index: Int, delta: Int): Pair<List<Category>, Int>? {
    val target = index + delta
    val lifted = categories.getOrNull(index) ?: return null
    val other = categories.getOrNull(target) ?: return null
    val moved = categories.toMutableList()
    moved[index] = other
    moved[target] = lifted
    return moved to target
}

/**
 * The crossbar's categories from the stored visible ones. Built-ins are rebuilt from [fallback]
 * (their names and glyphs are the launcher's own) but keep what the user stored for them —
 * colour, icon, filters, the gaming flag and their POSITION, so a built-in moved in Category
 * Manager really moves on the bar. Hidden built-ins are dropped, except Settings, which always
 * shows (at its default slot when its row is hidden). Custom categories pass through as stored.
 */
internal fun canonicalXmbCategories(categories: List<Category>, fallback: List<Category>): List<Category> {
    val byId = categories.associateBy { it.id }
    val builtInIds = fallback.map { it.id }.toSet()

    val builtIns = fallback.mapNotNull { default ->
        val stored = byId[default.id]
        if (stored == null && default.id != BuiltInCategory.SETTINGS) return@mapNotNull null
        default.copy(
            position         = stored?.position ?: default.position,
            accentColor      = stored?.accentColor,
            customIconUri    = stored?.customIconUri,
            filterRules      = stored?.filterRules,
            // Preserve the system-defined gaming flag from the DB (reconciled each launch);
            // without this the rebuilt Main Game category loses isGamingCategory, which hides
            // "Move to Category" for collections and suppresses live refresh.
            isGamingCategory = stored?.isGamingCategory ?: default.isGamingCategory,
        )
    }

    val customCategories = categories.filter { it.id !in builtInIds }

    return (builtIns + customCategories).sortedBy { it.position }
}

// Categories with their own dedicated layout — these never render custom memory cards and are
// not arrangeable at their root (media/system sections own their layouts).
internal val NON_COLLECTION_CATEGORY_IDS = setOf(
    BuiltInCategory.MUSIC, BuiltInCategory.VIDEO, BuiltInCategory.PHOTO, BuiltInCategory.ANDROID,
    BuiltInCategory.APP_DRAWER, BuiltInCategory.SETTINGS, BuiltInCategory.ACHIEVEMENTS,
    BuiltInCategory.SOCIAL,
)

/** True for categories that render custom memory cards: gaming categories, and the generic app
 *  categories (Network / App Store / custom non-gaming). */
internal fun categoryShowsCollections(category: Category?): Boolean {
    if (category == null) return false
    return category.isGamingCategory || category.id !in NON_COLLECTION_CATEGORY_IDS
}

/** The stored list the screen is showing, or null when what is on screen cannot be arranged. */
internal fun XMBUiState.currentListKey(): String? {
    val cat = categories.getOrNull(selectedCategoryIndex) ?: return null
    return when {
        cat.id == BuiltInCategory.MUSIC -> if (musicNav == MusicNav.MusicApps) ListKeys.apps(cat.id) else null
        cat.id == BuiltInCategory.VIDEO -> if (videoNav == VideoNav.VideoApps) ListKeys.apps(cat.id) else null
        cat.id == BuiltInCategory.PHOTO -> if (photoNav == PhotoNav.PhotoApps) ListKeys.apps(cat.id) else null
        !categoryShowsCollections(cat) -> null
        selectedCollectionId != null -> ListKeys.collection(selectedCollectionId)
        selectedPlatformId == XMBViewModel.ALL_GAMES_PLATFORM_ID -> ListKeys.ALL_GAMES
        selectedPlatformId == XMBViewModel.FAVORITES_PLATFORM_ID -> ListKeys.FAVORITES
        selectedPlatformId == XMBViewModel.MISSING_PLATFORM_ID -> MISSING_LIST_KEY
        selectedPlatformId == CATEGORY_CARD_PLATFORM_ID -> ListKeys.categoryCard(cat.id)
        selectedPlatformId != null -> ListKeys.card(selectedPlatformId)
        else -> ListKeys.root(cat.id)
    }
}

internal fun XMBUiState.currentListKind(): XmbListKind? {
    val cat = categories.getOrNull(selectedCategoryIndex) ?: return null
    val key = currentListKey() ?: return null
    return when {
        key.startsWith("apps:") -> XmbListKind.APPS
        selectedCollectionId != null || selectedPlatformId != null -> XmbListKind.GAMES
        // Main Game is a gaming column by definition, whatever its stored flag says.
        cat.isGamingCategory || cat.id == BuiltInCategory.GAMES -> XmbListKind.ROOT
        else -> XmbListKind.APPS
    }
}

internal const val MISSING_LIST_KEY = "missing"

/**
 * Stable key for whatever list the screen currently shows: the category id plus the drill level.
 * Cursor memory is stored under it, and the item column uses it to tell "a different list" from
 * "the same list stepping".
 */
internal fun XMBUiState.viewCursorKey(): String {
    val catId = categories.getOrNull(selectedCategoryIndex)?.id ?: "none"
    val sub = when {
        catId == BuiltInCategory.MUSIC -> "music_${musicNavKey(musicNav)}"
        catId == BuiltInCategory.VIDEO -> "video_${videoNavKey(videoNav)}"
        catId == BuiltInCategory.PHOTO -> "photo_${photoNavKey(photoNav)}"
        catId == BuiltInCategory.SOCIAL -> "social_${socialNavKey(socialNav)}"
        catId == BuiltInCategory.ACHIEVEMENTS -> "ach_${achievementsNavKey(achievementsNav)}"
        catId == BuiltInCategory.SETTINGS -> "settings_${settingsSectionNav?.id ?: "root"}"
        selectedCollectionId != null -> "col_$selectedCollectionId"
        selectedPlatformId != null   -> "plat_$selectedPlatformId"
        else                         -> "root"
    }
    return "$catId/$sub"
}

private fun musicNavKey(nav: MusicNav): String = when (nav) {
    MusicNav.Root        -> "root"
    MusicNav.AllMusic    -> "all"
    MusicNav.Playlists   -> "playlists"
    is MusicNav.Playlist -> "playlist_${nav.id}"
    MusicNav.MusicApps   -> "apps"
}

private fun videoNavKey(nav: VideoNav): String = when (nav) {
    VideoNav.Root            -> "root"
    VideoNav.AllVideos       -> "all"
    VideoNav.Collections     -> "collections"
    VideoNav.RecentlyWatched -> "recent"
    VideoNav.Favorites       -> "favorites"
    VideoNav.Playlists       -> "playlists"
    is VideoNav.Playlist     -> "playlist_${nav.id}"
    VideoNav.Libraries       -> "libraries"
    is VideoNav.Library      -> "library_${nav.id}"
    VideoNav.VideoApps       -> "apps"
}

private fun photoNavKey(nav: PhotoNav): String = when (nav) {
    PhotoNav.Root       -> "root"
    PhotoNav.AllPhotos  -> "all"
    PhotoNav.Albums     -> "albums"
    PhotoNav.PhotoApps  -> "apps"
    is PhotoNav.Library -> "library_${nav.id}"
}

// Each Social drill level needs its own cursor key — without this every level collides on the
// category's fallback "root" key, so drilling into a shorter list restores an out-of-range index
// and the selection highlight lands on nothing.
private fun socialNavKey(nav: SocialNav): String = when (nav) {
    SocialNav.Root             -> "root"
    SocialNav.Account          -> "account"
    SocialNav.Friends          -> "friends"
    SocialNav.Voice            -> "voice"
    SocialNav.VoiceSettings    -> "voicesettings"
    SocialNav.VoiceInvites     -> "voiceinvites"
    SocialNav.VoiceInviteFriends -> "voiceinvitefriends"
    SocialNav.ActivitySettings -> "activity"
    SocialNav.DiscordSettings  -> "discord"
}

private fun achievementsNavKey(nav: AchievementsNav): String = when (nav) {
    AchievementsNav.Root -> "root"
}

/** This row's key in its list's stored order, or null for a row that can never move. */
internal fun XMBItem.rowKey(): String? = when {
    type == XMBItemType.UMD_SLOT || type == XMBItemType.ADD_ACTION || type == XMBItemType.EMPTY -> null
    type == XMBItemType.ALL_GAMES -> ListKeys.ROW_ALL_GAMES
    type == XMBItemType.FAVORITES -> ListKeys.ROW_FAVORITES
    type == XMBItemType.MISSING -> ListKeys.ROW_MISSING
    type == XMBItemType.CATEGORY_CARD -> ListKeys.ROW_CATEGORY_CARD
    type == XMBItemType.COLLECTION && collectionId != null -> ListKeys.collectionItem(collectionId)
    type == XMBItemType.MEMORY_CARD && platformId != null -> ListKeys.cardItem(platformId)
    gameId != null -> ListKeys.game(gameId)
    packageName != null -> ListKeys.app(packageName)
    else -> null
}

// ── Sort tiers: a list's own sort, else the global one ────────────────────────

internal fun XmbSortMode.toListSort(): ListSortMode? = ListSortMode.fromName(name)

internal fun ListSortMode.toXmbSort(): XmbSortMode = XmbSortMode.valueOf(name)

/** The sort in force for [listKey]: its override, else the global sort for its [kind]. */
internal fun XMBUiState.activeSortFor(listKey: String?, kind: XmbListKind?): XmbSortMode {
    val global = if (kind == XmbListKind.APPS) appSortMode else gameSortMode
    return listSortOverrides[listKey] ?: global
}

/**
 * What the games-style list on screen holds, for choosing its sorts: an open custom card holds
 * what its category holds (apps in an app category), every other such list holds games.
 */
internal val XMBUiState.openListSortKind: XmbListKind
    get() {
        val categoryId = collections.firstOrNull { it.id == selectedCollectionId }?.categoryId
        return collectionSortKind(categories.firstOrNull { it.id == categoryId })
    }

/** The sort in force for the games list on screen. */
internal val XMBUiState.activeGameSort: XmbSortMode
    get() = activeSortFor(currentListKey(), openListSortKind)

/** Whether [listKey] is in the user's own order. A gaming root is either that or its default. */
internal fun XMBUiState.isCustomSorted(listKey: String?, kind: XmbListKind?): Boolean =
    if (kind == XmbListKind.ROOT) listSortOverrides[listKey] == XmbSortMode.CUSTOM
    else activeSortFor(listKey, kind) == XmbSortMode.CUSTOM

/** What a "Sort" row shows for [listKey]: its own sort, or the global one it follows. */
internal fun XMBUiState.sortValueLabel(listKey: String, kind: XmbListKind): String {
    val override = listSortOverrides[listKey]
    return when {
        kind == XmbListKind.ROOT -> if (override == XmbSortMode.CUSTOM) "Custom" else "Default"
        override != null -> sortLabel(override, kind)
        else -> "Global: ${sortLabel(activeSortFor(listKey, kind), kind)}"
    }
}

/** A sort's name on a list of [kind] — app lists say A–Z and Recently Used. */
internal fun sortLabel(mode: XmbSortMode, kind: XmbListKind): String = when {
    kind != XmbListKind.APPS -> mode.label
    mode == XmbSortMode.TITLE -> "A–Z"
    mode == XmbSortMode.RECENT_PLAYED -> "Recently Used"
    else -> mode.label
}

private val AUTOMATIC_SORTS = listOf(XmbSortMode.TITLE, XmbSortMode.RECENT_PLAYED, XmbSortMode.DATE_ADDED)

internal const val LIST_SORT_PREFIX = "lsort_"
internal const val LIST_SORT_DEFAULT_ID = "lsort_default"
internal const val GLOBAL_SORT_PREFIX = "gsort_"

/**
 * The rows of one list's Sort picker. A games or apps list can follow the global setting, take an
 * automatic sort of its own, or go Custom; a gaming root is only ever its default order or Custom.
 */
internal fun listSortMenuItems(
    kind: XmbListKind,
    global: XmbSortMode,
    override: XmbSortMode?,
): List<XMBContextMenuItem> =
    if (kind == XmbListKind.ROOT) {
        listOf(
            XMBContextMenuItem(LIST_SORT_DEFAULT_ID, "Default Order", checked = override != XmbSortMode.CUSTOM),
            XMBContextMenuItem(LIST_SORT_PREFIX + XmbSortMode.CUSTOM.name, "Custom", checked = override == XmbSortMode.CUSTOM),
        )
    } else buildList {
        add(
            XMBContextMenuItem(
                id = LIST_SORT_DEFAULT_ID,
                label = "Use Global Setting (${sortLabel(global, kind)})",
                checked = override == null,
            )
        )
        (AUTOMATIC_SORTS + XmbSortMode.CUSTOM).forEach { mode ->
            add(XMBContextMenuItem(LIST_SORT_PREFIX + mode.name, sortLabel(mode, kind), checked = override == mode))
        }
    }

/** The rows of the global Sort picker for lists of [kind]. Custom is per-list, so never here. */
internal fun globalSortMenuItems(kind: XmbListKind, current: XmbSortMode): List<XMBContextMenuItem> =
    AUTOMATIC_SORTS.map { mode ->
        XMBContextMenuItem(GLOBAL_SORT_PREFIX + mode.name, sortLabel(mode, kind), checked = mode == current)
    }

// ── Arranging rows ────────────────────────────────────────────────────────────

/**
 * Apps in the order [mode] asks for, pinned ones first. Name order is the base every other sort
 * falls back to, so two apps that tie (never used, same install time) still land predictably.
 */
internal fun List<CategorizedApp>.appSorted(mode: XmbSortMode, listState: ListState): List<CategorizedApp> {
    val byName = sortedBy { it.label.lowercase() }
    val sorted = when (mode) {
        XmbSortMode.RECENT_PLAYED -> byName.sortedByDescending { it.lastUsedAt }
        XmbSortMode.DATE_ADDED -> byName.sortedByDescending { it.installedAt }
        XmbSortMode.CUSTOM -> ListArrangement.customOrder(byName, listState.positions) { ListKeys.app(it.packageName) }
        else -> byName
    }
    return ListArrangement.pinnedFirst(sorted) { it.pinned }
}

/**
 * An app column's loose apps: an app placed in one of the column's own custom memory cards has
 * moved there, so it no longer shows at the root. It stays in the category, so leaving the card
 * (or deleting it) brings it straight back.
 */
internal fun List<CategorizedApp>.notInCards(cardedPackages: Set<String>): List<CategorizedApp> =
    if (cardedPackages.isEmpty()) this else filterNot { it.packageName in cardedPackages }

/**
 * [rows] (in the list's default order) as the list shows them: untouched in default order, or by
 * the stored Custom order with pinned rows gathered at the top.
 */
internal fun arrangeRows(rows: List<XMBItem>, listState: ListState, custom: Boolean): List<XMBItem> {
    if (!custom) return rows
    val ordered = ListArrangement.customOrder(rows.filter { it.rowKey() != null }, listState.positions) {
        it.rowKey().orEmpty()
    }
    return ListArrangement.pinnedFirst(ordered) { it.pinned } + rows.filter { it.rowKey() == null }
}

/**
 * A column's top level: the arrangeable rows with the UMD slot (when a game fills it) directly
 * above the column's default card, then the rows that always close the list (Add Games, an
 * empty-state row). Only the middle ever moves.
 */
internal fun assembleRoot(
    umd: XMBItem?,
    movable: List<XMBItem>,
    trailing: List<XMBItem>,
    listState: ListState,
    custom: Boolean,
): List<XMBItem> = arrangeRows(movable, listState, custom).withUmdAboveDefault(umd) + trailing

/** The card a gaming column lands on: All Games in Game, a custom category's own Memory Card. */
private fun XMBItem.isDefaultCard(): Boolean =
    type == XMBItemType.ALL_GAMES || type == XMBItemType.CATEGORY_CARD

/**
 * These rows with [umd] directly above the default card, wherever arrangement put it, so the
 * last-played or inserted game is always one step from where the column lands. With no default
 * card it tops the rows.
 */
private fun List<XMBItem>.withUmdAboveDefault(umd: XMBItem?): List<XMBItem> {
    if (umd == null) return this
    val at = indexOfFirst { it.isDefaultCard() }.coerceAtLeast(0)
    return take(at) + umd + drop(at)
}

// ── Move ──────────────────────────────────────────────────────────────────────

/**
 * [items] with the row at [index] moved one place by [delta], and the index it lands on. Null when
 * the move is not allowed: off the end, onto a fixed row (an Add row), across the line between
 * pinned and unpinned rows, or the UMD slot itself. The UMD slot is not a place in the order: it
 * rides above the default card, so moving that card carries it, and a row passing the card
 * passes it too.
 */
internal fun moveRow(items: List<XMBItem>, index: Int, delta: Int): Pair<List<XMBItem>, Int>? {
    val umdAt = items.indexOfFirst { it.type == XMBItemType.UMD_SLOT }
    if (umdAt < 0) return swapRows(items, index, delta)
    if (index == umdAt) return null
    val rest = items.filterIndexed { i, _ -> i != umdAt }
    val (moved, _) = swapRows(rest, if (index > umdAt) index - 1 else index, delta) ?: return null
    val placed = moved.withUmdAboveDefault(items[umdAt])
    return placed to placed.indexOf(items[index])
}

private fun swapRows(items: List<XMBItem>, index: Int, delta: Int): Pair<List<XMBItem>, Int>? {
    val target = index + delta
    val row = items.getOrNull(index) ?: return null
    val other = items.getOrNull(target) ?: return null
    if (row.rowKey() == null || other.rowKey() == null || row.pinned != other.pinned) return null
    val moved = items.toMutableList()
    moved[index] = other
    moved[target] = row
    return moved to target
}

/** The order to save after a move: every movable row's key, top to bottom. */
internal fun List<XMBItem>.orderKeys(): List<String> = mapNotNull { it.rowKey() }

/**
 * The arrange rows of a context menu. [pinned] is null for a row that cannot be pinned (All
 * Games, a category's own Memory Card); [canMove] is true only while the row's list is Custom
 * sorted, so the menu never offers a move the sort would undo.
 */
internal fun arrangeMenuItems(pinned: Boolean?, canMove: Boolean): List<XMBContextMenuItem> = buildList {
    if (pinned != null) {
        add(XMBContextMenuItem(if (pinned) "unpin_top" else "pin_top", "Pin to Top", value = if (pinned) "On" else "Off"))
    }
    if (canMove) add(XMBContextMenuItem(MOVE_ROW_ID, "Move"))
}

/** Adds [rows] with [header] on the first of them. An empty group adds nothing, header included. */
internal fun MutableList<XMBContextMenuItem>.group(
    header: String,
    rows: MutableList<XMBContextMenuItem>.() -> Unit,
) {
    val items = mutableListOf<XMBContextMenuItem>().apply(rows)
    items.forEachIndexed { index, item -> add(if (index == 0) item.copy(header = header) else item) }
}

internal const val MOVE_ROW_ID = "move_row"
internal const val LIST_SORT_ROW_ID = "list_sort"
internal const val GLOBAL_SORT_ROW_ID = "sort_global"

/** What a game's menu offers about its column's UMD slot. */
enum class UmdMenuState {
    /** Not in a gaming column, or nothing to offer. */
    NONE,
    /** The game can be inserted as this column's UMD. */
    CAN_INSERT,
    /** The game is the one inserted; it can be ejected. */
    INSERTED,
    /** The UMD row shows the recently played fallback: insert it to keep it, or eject it. */
    RECENT,
}

/** The UMD row of a game's menu, or none. */
internal fun umdMenuItems(state: UmdMenuState): List<XMBContextMenuItem> = when (state) {
    UmdMenuState.NONE -> emptyList()
    UmdMenuState.CAN_INSERT -> listOf(XMBContextMenuItem("insert_umd", "Insert as UMD"))
    UmdMenuState.INSERTED -> listOf(XMBContextMenuItem("eject_umd", "Eject UMD"))
    UmdMenuState.RECENT -> listOf(
        XMBContextMenuItem("insert_umd", "Insert as UMD"),
        XMBContextMenuItem("eject_umd", "Eject UMD"),
    )
}

/**
 * The menu of a container row that is not a record of its own — a custom category's Memory Card,
 * Favorites, Missing: open it, set the sort of the list behind it, and move it while its root is
 * Custom sorted. "Open" only for [byTouch]: on a pad it repeats the press, so Missing — which has
 * nothing else — has no controller menu at all.
 */
internal fun rootRowMenuItems(sortValue: String?, canMove: Boolean, byTouch: Boolean): List<XMBContextMenuItem> = buildList {
    if (byTouch) add(XMBContextMenuItem("open_row", "Open"))
    if (sortValue != null) add(XMBContextMenuItem(LIST_SORT_ROW_ID, "Sort", value = sortValue, opensMenu = true))
    addAll(arrangeMenuItems(pinned = null, canMove = canMove))
}

/**
 * A custom memory card row's menu. The verbs are today's, renamed; Delete says "Custom Card" so
 * it can never be mistaken for a console card's Remove, which takes that console's games with it.
 * It leads with the card's own picker: Add Games for a card in a gaming category, Add Apps for
 * one in an app category ([holds]).
 */
internal fun customCardMenuItems(
    pinned: Boolean,
    canMoveToCategory: Boolean,
    sortValue: String,
    canMove: Boolean,
    byTouch: Boolean,
    holds: CardContents = CardContents.GAMES,
): List<XMBContextMenuItem> = buildList {
    if (byTouch) add(XMBContextMenuItem("open_collection", "Open"))
    when (holds) {
        CardContents.GAMES -> add(XMBContextMenuItem(ADD_GAMES_TO_CARD_ID, "Add Games"))
        CardContents.APPS -> add(XMBContextMenuItem(ADD_APPS_TO_CARD_ID, "Add Apps"))
    }
    add(XMBContextMenuItem(LIST_SORT_ROW_ID, "Sort", value = sortValue, opensMenu = true))
    add(XMBContextMenuItem(if (pinned) "unpin_collection" else "pin_collection", "Pin to Top", value = if (pinned) "On" else "Off"))
    if (canMove) add(XMBContextMenuItem(MOVE_ROW_ID, "Move"))
    add(XMBContextMenuItem("rename_collection", "Rename Card"))
    if (canMoveToCategory) add(XMBContextMenuItem("move_collection_category", "Move to Category", opensMenu = true))
    add(XMBContextMenuItem("manage_collections", "Manage Custom Cards"))
    add(XMBContextMenuItem("delete_collection", "Delete Custom Card", isDestructive = true))
}

internal const val ADD_GAMES_TO_CARD_ID = "add_games_collection"
internal const val ADD_APPS_TO_CARD_ID = "add_apps_collection"

/** What a custom memory card holds, from its category: games in a gaming one, app shortcuts otherwise. */
internal enum class CardContents { GAMES, APPS }

/**
 * What confirming the game picker changes on a list (a category's loose games or a custom card):
 * the games to add — checked and not [already] there, so a re-add never resets when it was added —
 * and the games to remove: only those the picker opened checked ([preselected]) and the user then
 * unchecked. Judging by absence alone would empty the list if the pre-check never loaded.
 */
internal fun gamePickerChanges(already: Set<Long>, preselected: Set<Long>, selected: Set<Long>): Pair<Set<Long>, Set<Long>> =
    (selected - already) to (preselected - selected)

/** What a custom card in [category] holds, so its Sort offers that list's sorts: apps in an app category. */
internal fun collectionSortKind(category: Category?): XmbListKind =
    if (category != null && !category.isGamingCategory) XmbListKind.APPS else XmbListKind.GAMES

/**
 * The rows of "Add to Card": the custom memory cards of the category being browsed first, then
 * every other category's (named for where they live), then the row that makes a new one here.
 * [collections] are (id, name, categoryId); [memberOf] marks the ones the game is already in.
 */
internal fun addToCardMenuItems(
    collections: List<Triple<Long, String, String>>,
    currentCategoryId: String?,
    categoryNames: Map<String, String>,
    memberOf: Set<Long>,
): List<XMBContextMenuItem> = buildList {
    val (here, elsewhere) = collections.partition { it.third == currentCategoryId }
    here.forEachIndexed { index, (id, name, _) ->
        add(
            XMBContextMenuItem(
                id = "col_$id",
                label = name,
                checked = id in memberOf,
                header = if (index == 0) categoryNames[currentCategoryId]?.let { "In $it" } else null,
            )
        )
    }
    elsewhere.forEachIndexed { index, (id, name, categoryId) ->
        add(
            XMBContextMenuItem(
                id = "col_$id",
                label = name,
                checked = id in memberOf,
                value = categoryNames[categoryId],
                header = if (index == 0 && here.isNotEmpty()) "Other Categories" else null,
            )
        )
    }
    add(XMBContextMenuItem("col_new", "New Custom Card Here…"))
}

/**
 * An app row as non-gaming lists show it: the name alone, plus "Pinned" when pinned. Apps inside
 * a custom memory card come through the games-table row builder, whose platform/emulator line
 * belongs to games, so the card strips it back to this.
 */
internal fun XMBItem.asAppRow(): XMBItem = copy(subtitle = if (pinned) "Pinned" else null)

// ── Landing on a section ──────────────────────────────────────────────────────

/**
 * The row moving onto [category] lands on, PSP-style: the section's memory card where it has one
 * (Photos, Videos, Music — or Now Playing while a track is loaded — All Games, a custom gaming
 * category's own Memory Card), otherwise the top row. A default row that is absent falls back to
 * the top.
 */
internal fun defaultRootIndex(category: Category, items: List<XMBItem>): Int {
    val preferred = when (category.id) {
        BuiltInCategory.PHOTO -> listOf(XMBViewModel.ALL_PHOTOS_ITEM_ID)
        BuiltInCategory.MUSIC -> listOf(XMBViewModel.NOW_PLAYING_ITEM_ID, XMBViewModel.ALL_MUSIC_ITEM_ID)
        BuiltInCategory.VIDEO -> listOf(XMBViewModel.ALL_VIDEOS_ITEM_ID)
        BuiltInCategory.GAMES -> listOf(XMBViewModel.ALL_GAMES_ITEM_ID)
        else -> if (category.isGamingCategory) listOf(XMBViewModel.CATEGORY_CARD_ITEM_ID) else emptyList()
    }
    return preferred.firstNotNullOfOrNull { id -> items.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: 0
}

/**
 * This state after a write from [before], with a pending landing resolved: the first fresh,
 * non-empty root rows of the section put the cursor on its [defaultRootIndex] and bump the
 * landing token so the list snaps there. Rows left over from the previous section (unchanged by
 * the write) and a blanked list are not a landing; drilling in cancels it, and nothing changes
 * once the landing is done.
 */
internal fun XMBUiState.landedFrom(before: XMBUiState): XMBUiState {
    if (!landingPending) return this
    // Drilled in before the rows came (or the section re-published the very same rows): there is
    // nothing left to land on, and backing out restores its own cursor.
    if (isInSubItem) return copy(landingPending = false)
    if (currentItems === before.currentItems || currentItems.isEmpty()) return this
    val category = categories.getOrNull(selectedCategoryIndex) ?: return this
    return copy(
        selectedItemIndex = defaultRootIndex(category, currentItems),
        landingPending = false,
        landingToken = landingToken + 1,
    )
}

/**
 * A console Memory Card row's title: the console, as the game picker's shelves show it. A default
 * "{console} Memory Card" name drops the ending, which moves to [memoryCardRowSubtitle]; a name the
 * user typed is kept as written.
 */
internal fun memoryCardRowTitle(card: MemoryCard): String = shelfTitle(card)

/** "Memory Card · 24 Games", the console-card twin of a custom card's "Custom · 12 Games". */
internal fun memoryCardRowSubtitle(count: Int, pinned: Boolean): String {
    val games = "$count ${if (count == 1) "Game" else "Games"}"
    return if (pinned) "Memory Card · Pinned · $games" else "Memory Card · $games"
}
