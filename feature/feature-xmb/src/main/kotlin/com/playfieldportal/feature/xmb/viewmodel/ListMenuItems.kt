package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.PfpNotification

/**
 * The XMB's list-level menus (the ones that act on a list or an account rather than on a row), from
 * the state they depend on. Pure so they can be pinned without building [XMBViewModel]; ids are the
 * handlers' keys and do not change with the wording.
 */

/**
 * The notification panel's menu. A row that cannot act is not offered: Mark All Read needs something
 * unread, Clear Read something read, Clear All any history. An empty list yields no rows, and the
 * caller then opens no menu. Clear All also leaves running work alone — those tasks post their own
 * fresh rows when they finish.
 */
internal fun notificationListMenuItems(history: List<PfpNotification>): List<XMBContextMenuItem> = buildList {
    if (history.any { !it.isRead }) add(XMBContextMenuItem(NotificationMenuIds.MARK_ALL_READ, "Mark All Read"))
    if (history.any { it.isRead }) add(XMBContextMenuItem(NotificationMenuIds.CLEAR_READ, "Clear Read"))
    if (history.isNotEmpty()) add(XMBContextMenuItem(NotificationMenuIds.CLEAR_ALL, "Clear All", isDestructive = true))
}

/** The connected-account menu is titled with the account's name, "Account" when the row has none. */
internal fun socialAccountMenuTitle(rowTitle: String?): String = rowTitle?.takeIf { it.isNotBlank() } ?: "Account"

internal const val BROWSER_SORT_MENU_ID = "music_browser_sort"
private const val BROWSER_SORT_MODE_PREFIX = "music_browser_sort_"

/**
 * The fullscreen music browser's list-level rows: Resume (the track as its value), Sort (the mode as
 * its value, opening the sort list; absent when the list does not sort) and Import Playlist
 * (Playlists view only, after Sort).
 */
internal fun browserListMenuItems(
    resumeTrack: String?,
    sortLabel: String?,
    view: MusicBrowserView?,
): List<XMBContextMenuItem> = buildList {
    // Resume first: it is the reason to open this menu mid-song, and without it a player dismissed
    // with B can only be recovered by finding and re-picking the track.
    resumeTrack?.let { add(XMBContextMenuItem("music_browser_resume", "Resume", value = it)) }
    sortLabel?.let { add(XMBContextMenuItem(BROWSER_SORT_MENU_ID, "Sort", value = it, opensMenu = true)) }
    addAll(browserImportMenuItems(view))
}

/** The sort list the browser's Sort row opens: [modes] in order, [current] checked. */
internal fun browserSortMenuItems(modes: List<XmbSortMode>, current: XmbSortMode): List<XMBContextMenuItem> =
    modes.map { XMBContextMenuItem("$BROWSER_SORT_MODE_PREFIX${it.name}", it.label, checked = it == current) }

/** The mode a sort-list row id names, or null for any other id. */
internal fun browserSortModeOf(itemId: String): XmbSortMode? =
    itemId.removePrefix(BROWSER_SORT_MODE_PREFIX).takeIf { itemId.startsWith(BROWSER_SORT_MODE_PREFIX) }
        ?.let { name -> XmbSortMode.entries.firstOrNull { it.name == name } }
