package com.playfieldportal.feature.xmb.viewmodel

/**
 * The Games root's two card menus, from the state they depend on. Kept out of [XMBViewModel] for
 * the same reason as [gameContextMenuItems]: their contents can be pinned without building it.
 *
 * Both menus are the same four groups in the same order — Games, Update, Display, Manage — so the
 * All Games card reads as the whole-library version of a platform card, and a row's place on one
 * is its place on the other. Ids are shared where the action is the same one at a different scope;
 * the menu it was chosen from says which scope.
 */

private const val GROUP_GAMES = "Games"
private const val GROUP_UPDATE = "Update"
private const val GROUP_DISPLAY = "Display"
private const val GROUP_MANAGE = "Manage"

/**
 * One platform Memory Card's menu.
 *
 * [iconDisplayLabel] is the card's own override, or the global mode it is following; [sortLabel]
 * is the same for the sort of the card's game list. [canMove] is true only while the Games root
 * is Custom sorted.
 */
internal fun platformCardMenuItems(
    platformId: String,
    pinned: Boolean,
    iconDisplayLabel: String,
    sortLabel: String? = null,
    canMove: Boolean = false,
): List<XMBContextMenuItem> = buildList {
    val isWindows = platformId == XMBViewModel.WINDOWS_PLATFORM_ID

    group(GROUP_GAMES) {
        // Android libraries pick installed apps; consoles scan ROM folders.
        if (platformId == XMBViewModel.ANDROID_PLATFORM_ID) add(XMBContextMenuItem("find_games", "Find Games"))
        else add(XMBContextMenuItem("scan_roms", "Scan for Games"))
        // The Windows card is import-driven, and this is the only card menu that offers it.
        if (isWindows) add(XMBContextMenuItem("import_pc_games", "Import PC Games"))
    }
    group(GROUP_UPDATE) {
        add(XMBContextMenuItem("update_metadata", "Update Metadata"))
        add(XMBContextMenuItem("scrape_missing_artwork", "Fetch Missing Artwork"))
        // Achievement tracking for emulated Windows games: one folder pick covers a whole
        // library. Offered here as well as in settings because this is the card those games
        // live on, and the card's own scan deliberately does no emulator work.
        if (isWindows) add(XMBContextMenuItem("batch_match_local", "Match Achievements"))
    }
    group(GROUP_DISPLAY) {
        // Icon display for THIS console only. Games on other Memory Cards are untouched;
        // "Use Global Setting" in its picker clears the console's override.
        add(XMBContextMenuItem("icon_display_platform", "Icon Display", value = iconDisplayLabel))
        // Sort for THIS card's games only, the same way: its own, or the global one it follows.
        if (sortLabel != null) add(XMBContextMenuItem(LIST_SORT_ROW_ID, "Sort", value = sortLabel))
        // One name whichever way it is set: a label that flips leaves a user nothing to learn
        // but the row's position.
        add(XMBContextMenuItem(if (pinned) "unpin" else "pin", "Pin to Top", value = if (pinned) "On" else "Off"))
        if (canMove) add(XMBContextMenuItem(MOVE_ROW_ID, "Move"))
    }
    group(GROUP_MANAGE) {
        add(XMBContextMenuItem("library_manager", "Library Manager"))
        add(XMBContextMenuItem("hide", "Hide Card"))
        // The Windows Memory Card is managed by the PC import system and cannot be removed.
        if (!isWindows) add(XMBContextMenuItem("remove", "Remove Card", isDestructive = true))
    }
}

/**
 * The "All Games" card's menu. It is not a real Memory Card, so it has nothing to pin, hide or
 * remove — and nothing platform-specific either: Import PC Games lives on the Windows card.
 *
 * It carries both sort rows, as it does for Icon Display: [sortLabel] for the All Games list
 * itself, and [globalSortLabel] for every game list that has no sort of its own.
 */
internal fun allGamesMenuItems(
    iconDisplayLabel: String,
    sortLabel: String? = null,
    globalSortLabel: String? = null,
    canMove: Boolean = false,
): List<XMBContextMenuItem> = buildList {
    group(GROUP_GAMES) {
        add(XMBContextMenuItem("scan_all", "Scan All Cards"))
    }
    group(GROUP_UPDATE) {
        add(XMBContextMenuItem("update_metadata", "Update Metadata"))
        add(XMBContextMenuItem("scrape_missing_artwork", "Fetch Missing Artwork"))
        // Full-library Scan & Relink from the card the whole collection lives on (C22 task T3).
        // Runs as a worker, so it survives leaving this screen.
        add(XMBContextMenuItem("relink_artwork", "Relink Artwork"))
    }
    group(GROUP_DISPLAY) {
        add(XMBContextMenuItem("icon_display_global", "Icon Display", value = iconDisplayLabel))
        if (sortLabel != null) add(XMBContextMenuItem(LIST_SORT_ROW_ID, "Sort", value = sortLabel))
        if (globalSortLabel != null) add(XMBContextMenuItem(GLOBAL_SORT_ROW_ID, "Global Sort", value = globalSortLabel))
        if (canMove) add(XMBContextMenuItem(MOVE_ROW_ID, "Move"))
    }
    group(GROUP_MANAGE) {
        add(XMBContextMenuItem("library_manager", "Library Manager"))
    }
}

/** Adds [rows] with [header] on the first of them. An empty group adds nothing, header included. */
private fun MutableList<XMBContextMenuItem>.group(
    header: String,
    rows: MutableList<XMBContextMenuItem>.() -> Unit,
) {
    val items = mutableListOf<XMBContextMenuItem>().apply(rows)
    items.forEachIndexed { index, item -> add(if (index == 0) item.copy(header = header) else item) }
}

/**
 * What Scan All Cards settles its tray row with.
 *
 * A card that could not be scanned is always named: "nothing new" and "could not look" lead to
 * opposite actions, so one must never be reported as the other.
 */
internal fun scanAllSummary(added: Int, cards: Int, failed: Int): String {
    val found = when (added) {
        0 -> "No new games found"
        else -> "$added new ${if (added == 1) "game" else "games"} across " +
            "$cards ${if (cards == 1) "Memory Card" else "Memory Cards"}"
    }
    if (failed == 0) return found
    return "$found · $failed ${if (failed == 1) "card" else "cards"} could not be scanned"
}
