package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.feature.xmb.R

/**
 * The Games root's two card menus, from the state they depend on. Kept out of [XMBViewModel] for
 * the same reason as [gameContextMenuItems]: their contents can be pinned without building it.
 *
 * Both menus are the same four groups in the same order — Games, Update, Display, Manage — so the
 * All Games card reads as the whole-library version of a platform card, and a row's place on one
 * is its place on the other. Ids are shared where the action is the same one at a different scope;
 * the menu it was chosen from says which scope.
 */

private val GROUP_GAMES = UiText.res(R.string.xmbm_group_games)
private val GROUP_UPDATE = UiText.res(R.string.xmbm_group_update)
private val GROUP_DISPLAY = UiText.res(R.string.xmbm_group_display)
private val GROUP_MANAGE = UiText.res(R.string.xmbm_group_manage)

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
    iconDisplayLabel: UiText,
    sortLabel: UiText? = null,
    canMove: Boolean = false,
): List<XMBContextMenuItem> = buildList {
    val isWindows = platformId == XMBViewModel.WINDOWS_PLATFORM_ID

    group(GROUP_GAMES) {
        // Android libraries pick installed apps; consoles scan ROM folders.
        if (platformId == XMBViewModel.ANDROID_PLATFORM_ID) add(XMBContextMenuItem("find_games", UiText.res(R.string.xmbm_card_find_games)))
        else add(XMBContextMenuItem("scan_roms", UiText.res(R.string.xmbm_card_scan_roms)))
        // The Windows card is import-driven, and this is the only card menu that offers it.
        if (isWindows) add(XMBContextMenuItem("import_pc_games", UiText.res(R.string.xmbm_card_import_pc)))
    }
    group(GROUP_UPDATE) {
        add(XMBContextMenuItem("update_metadata", UiText.res(R.string.xmbm_update_metadata)))
        add(XMBContextMenuItem("scrape_missing_artwork", UiText.res(R.string.xmbm_fetch_missing_artwork)))
        // Achievement tracking for emulated Windows games: one folder pick covers a whole
        // library. Offered here as well as in settings because this is the card those games
        // live on, and the card's own scan deliberately does no emulator work.
        if (isWindows) add(XMBContextMenuItem("batch_match_local", UiText.res(R.string.xmbm_card_match_achievements)))
    }
    group(GROUP_DISPLAY) {
        // Icon display for THIS console only. Games on other Memory Cards are untouched;
        // "Use Global Setting" in its picker clears the console's override.
        add(XMBContextMenuItem("icon_display_platform", UiText.res(R.string.xmbm_icon_display), value = iconDisplayLabel, opensMenu = true))
        // Sort for THIS card's games only, the same way: its own, or the global one it follows.
        if (sortLabel != null) add(XMBContextMenuItem(LIST_SORT_ROW_ID, UiText.res(R.string.xmbm_sort), value = sortLabel, opensMenu = true))
        // One name whichever way it is set: a label that flips leaves a user nothing to learn
        // but the row's position.
        add(XMBContextMenuItem(if (pinned) "unpin" else "pin", UiText.res(R.string.xmbm_pin_to_top), value = onOff(pinned)))
        if (canMove) add(XMBContextMenuItem(MOVE_ROW_ID, UiText.res(R.string.xmbm_move)))
    }
    group(GROUP_MANAGE) {
        add(XMBContextMenuItem("library_manager", UiText.res(R.string.xmbm_library_manager)))
        add(XMBContextMenuItem("hide", UiText.res(R.string.xmbm_card_hide)))
        // The Windows Memory Card is managed by the PC import system and cannot be removed.
        if (!isWindows) add(XMBContextMenuItem("remove", UiText.res(R.string.xmbm_card_remove), isDestructive = true))
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
    iconDisplayLabel: UiText,
    sortLabel: UiText? = null,
    globalSortLabel: UiText? = null,
    canMove: Boolean = false,
): List<XMBContextMenuItem> = buildList {
    group(GROUP_GAMES) {
        add(XMBContextMenuItem("scan_all", UiText.res(R.string.xmbm_card_scan_all)))
    }
    group(GROUP_UPDATE) {
        add(XMBContextMenuItem("update_metadata", UiText.res(R.string.xmbm_update_metadata)))
        add(XMBContextMenuItem("scrape_missing_artwork", UiText.res(R.string.xmbm_fetch_missing_artwork)))
        // Full-library Scan & Relink from the card the whole collection lives on (C22 task T3).
        // Runs as a worker, so it survives leaving this screen.
        add(XMBContextMenuItem("relink_artwork", UiText.res(R.string.xmbm_card_relink_artwork)))
    }
    group(GROUP_DISPLAY) {
        add(XMBContextMenuItem("icon_display_global", UiText.res(R.string.xmbm_icon_display), value = iconDisplayLabel, opensMenu = true))
        if (sortLabel != null) add(XMBContextMenuItem(LIST_SORT_ROW_ID, UiText.res(R.string.xmbm_sort), value = sortLabel, opensMenu = true))
        if (globalSortLabel != null) add(XMBContextMenuItem(GLOBAL_SORT_ROW_ID, UiText.res(R.string.xmbm_card_global_sort), value = globalSortLabel, opensMenu = true))
        if (canMove) add(XMBContextMenuItem(MOVE_ROW_ID, UiText.res(R.string.xmbm_move)))
    }
    group(GROUP_MANAGE) {
        add(XMBContextMenuItem("library_manager", UiText.res(R.string.xmbm_library_manager)))
    }
}

/**
 * What Scan All Cards settles its tray row with.
 *
 * A card that could not be scanned is always named: "nothing new" and "could not look" lead to
 * opposite actions, so one must never be reported as the other.
 */
internal fun scanAllSummary(added: Int, cards: Int, failed: Int): UiText {
    val found = when (added) {
        0 -> UiText.res(R.string.xmbm_scan_none_new)
        // [cards] is every card scanned, not the ones that found games (survey U25).
        else -> UiText.Joined(
            listOf(
                UiText.plural(R.plurals.xmbm_scan_new_games, added),
                UiText.plural(R.plurals.xmbm_scan_cards_scanned, cards),
            ),
            " · ",
        )
    }
    if (failed == 0) return found
    return UiText.Joined(listOf(found, UiText.plural(R.plurals.xmbm_scan_cards_failed, failed)), " · ")
}
