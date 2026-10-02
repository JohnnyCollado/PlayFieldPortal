package com.playfieldportal.feature.appbar

/** Ids of the rows [appMenuItems] draws. Handlers key on these; wording changes never touch them. */
object AppMenuIds {
    const val LAUNCH = "launch"
    const val EDIT_DETAILS = "edit_app"
    const val MARK_GAME = "mark_game"
    const val UNMARK_GAME = "unmark_game"
    const val FAVORITE = "favorite_toggle"
    const val ADD_TO_CARD = "add_to_collection"
    const val PIN = "pin"
    const val UNPIN = "unpin"
    /** Must equal the XMB's own Move row id (`MOVE_ROW_ID`): the same handler runs it. */
    const val MOVE_ROW = "move_row"
    const val MOVE_CATEGORY = "move"
    const val ADD_CATEGORY = "add"
    const val REMOVE_CATEGORY = "remove"
    const val RENAME = "rename"
    const val HIDE_FROM = "hide_from_category"
    const val HIDE_EVERYWHERE = "hide_everywhere"
    const val APP_INFO = "app_info"
    const val UNINSTALL = "app_uninstall"
}

/** One row of an app's context menu, host-neutral: the XMB and the App Drawer each map it to their own row type. */
data class AppMenuEntry(
    val id: String,
    val label: String,
    val value: String? = null,
    /** A group name drawn above this row — set on the first row of each group. */
    val header: String? = null,
    /** Activating the row opens a list: the panel draws › and the cue is SELECT. */
    val opensMenu: Boolean = false,
    val isDestructive: Boolean = false,
    /** Activating the row plays no cue (Favorite). */
    val silent: Boolean = false,
)

/**
 * What an app's menu depends on. [isFavorite] is the app's launch-shortcut row's state, resolved
 * before the menu is published. [categoryId] / [categoryName] are the place the app is shown
 * (null from the App Drawer, which has none). [pinned] is its Pin to Top state there and [canMove]
 * is true only while that list is Custom sorted. [isGame] is whether the app is marked as a game.
 */
data class AppMenuContext(
    val byTouch: Boolean,
    val isFavorite: Boolean,
    val categoryId: String?,
    val categoryName: String?,
    val pinned: Boolean,
    val canMove: Boolean,
    val isSystemApp: Boolean,
    val isGame: Boolean = false,
)

/**
 * An app's context menu, in three groups: Library, Arrange, Manage. An empty group draws nothing,
 * header included. Launch is only offered to a touch opener: from the controller it would repeat
 * the A press that opens the app. Uninstall is last and red, and not offered for system apps.
 */
fun appMenuItems(context: AppMenuContext): List<AppMenuEntry> = buildList {
    group("Library") {
        if (context.byTouch) add(AppMenuEntry(AppMenuIds.LAUNCH, "Launch"))
        add(AppMenuEntry(AppMenuIds.EDIT_DETAILS, "Edit App Details"))
        // Promotes the app into the Android Memory Card as a real game.
        if (context.isGame) add(AppMenuEntry(AppMenuIds.UNMARK_GAME, "Unmark as Game"))
        else add(AppMenuEntry(AppMenuIds.MARK_GAME, "Mark as Game"))
        // One name and one id whichever way it is set; the value says the state.
        add(AppMenuEntry(AppMenuIds.FAVORITE, "Favorite", value = if (context.isFavorite) "On" else "Off", silent = true))
        add(AppMenuEntry(AppMenuIds.ADD_TO_CARD, "Add to Card", opensMenu = true))
    }

    group("Arrange") {
        if (context.categoryId != null) {
            add(AppMenuEntry(if (context.pinned) AppMenuIds.UNPIN else AppMenuIds.PIN, "Pin to Top", value = if (context.pinned) "On" else "Off"))
            if (context.canMove) add(AppMenuEntry(AppMenuIds.MOVE_ROW, "Move"))
        }
        add(AppMenuEntry(AppMenuIds.MOVE_CATEGORY, "Move to Category", opensMenu = true))
        add(AppMenuEntry(AppMenuIds.ADD_CATEGORY, "Add to Category", opensMenu = true))
        if (context.categoryId != null) add(AppMenuEntry(AppMenuIds.REMOVE_CATEGORY, "Remove from Category"))
    }

    group("Manage") {
        add(AppMenuEntry(AppMenuIds.RENAME, "Rename Shortcut"))
        // Per-location hide (recoverable in Settings ▸ Hidden Items) + global hide-everywhere.
        if (context.categoryId != null && context.categoryName != null) {
            add(AppMenuEntry(AppMenuIds.HIDE_FROM, "Hide from ${context.categoryName}"))
        }
        add(AppMenuEntry(AppMenuIds.HIDE_EVERYWHERE, "Hide Everywhere"))
        add(AppMenuEntry(AppMenuIds.APP_INFO, "App Info"))
        if (!context.isSystemApp) add(AppMenuEntry(AppMenuIds.UNINSTALL, "Uninstall", isDestructive = true))
    }
}

/**
 * The App Drawer's menu: [appMenuItems] with no place to be shown in, cut to the six rows the drawer
 * offers and drawn ungrouped. Mark as Game is one row whichever way it is set, its value saying the
 * state, like Favorite. The id still follows the state, so a handler tells promote from demote.
 */
fun appDrawerMenuItems(isFavorite: Boolean, isGame: Boolean, isSystemApp: Boolean): List<AppMenuEntry> {
    val shared = appMenuItems(
        AppMenuContext(
            byTouch = false, isFavorite = isFavorite, categoryId = null, categoryName = null,
            pinned = false, canMove = false, isSystemApp = isSystemApp, isGame = isGame,
        ),
    )
    val markId = if (isGame) AppMenuIds.UNMARK_GAME else AppMenuIds.MARK_GAME
    val order = listOf(
        AppMenuIds.EDIT_DETAILS, AppMenuIds.FAVORITE, AppMenuIds.ADD_TO_CARD, markId,
        AppMenuIds.APP_INFO, AppMenuIds.UNINSTALL,
    )
    return order.mapNotNull { id -> shared.firstOrNull { it.id == id } }.map { entry ->
        val plain = entry.copy(header = null)
        if (entry.id == markId) plain.copy(label = "Mark as Game", value = if (isGame) "On" else "Off") else plain
    }
}

private fun MutableList<AppMenuEntry>.group(header: String, rows: MutableList<AppMenuEntry>.() -> Unit) {
    val entries = mutableListOf<AppMenuEntry>().apply(rows)
    entries.forEachIndexed { index, entry -> add(if (index == 0) entry.copy(header = header) else entry) }
}
