package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.ui.icons.UserCategoryIconKeys
import com.playfieldportal.feature.settings.viewmodel.iconValueLabel

/**
 * The menu a long-press on a category icon opens. Rename and Change Icon hand off to Category
 * Manager's own screens for that category (so the name rules and the icon picker, "From Your
 * Device" included, stay in one place); the rest act on the bar itself. Kept out of
 * [XMBViewModel] so the rows can be pinned without building it.
 */

internal const val CATEGORY_MENU_RENAME_ID = "catmenu_rename"
internal const val CATEGORY_MENU_ICON_ID = "catmenu_icon"
internal const val CATEGORY_MENU_VISIBLE_ID = "catmenu_visible"
internal const val CATEGORY_MENU_MOVE_ID = "catmenu_move"
internal const val CATEGORY_MENU_MANAGE_ID = "catmenu_manage"
internal const val CATEGORY_MENU_NEW_CARD_ID = "catmenu_new_card"

/**
 * The rows for [categoryId]. [iconLabel] is what Change Icon prints as its value; [visible] is
 * whether the category is on the bar now — a hidden one cannot be lifted, so Move goes with it.
 * Settings is the only way back into category management, so it has no Show on Bar.
 * [showsCards] adds New Custom Card for a column that lists custom memory cards, so a card no
 * longer has to be made from an item's Add to Card submenu (survey L29).
 */
internal fun categoryMenuItems(
    categoryId: String,
    iconLabel: String,
    visible: Boolean,
    showsCards: Boolean = false,
): List<XMBContextMenuItem> = buildList {
    add(XMBContextMenuItem(CATEGORY_MENU_RENAME_ID, "Rename Category"))
    add(XMBContextMenuItem(CATEGORY_MENU_ICON_ID, "Change Icon", value = iconLabel, opensMenu = true))
    if (categoryId != BuiltInCategory.SETTINGS) {
        add(XMBContextMenuItem(CATEGORY_MENU_VISIBLE_ID, "Show on Bar", value = if (visible) "On" else "Off"))
    }
    if (visible) add(XMBContextMenuItem(CATEGORY_MENU_MOVE_ID, "Move"))
    if (showsCards) add(XMBContextMenuItem(CATEGORY_MENU_NEW_CARD_ID, "New Custom Card"))
    add(XMBContextMenuItem(CATEGORY_MENU_MANAGE_ID, "Manage Categories"))
}

/** [category]'s menu; [hasImage] is whether a device image stands in for its catalog icon. */
internal fun categoryContextMenu(category: Category, hasImage: Boolean): XMBContextMenu = XMBContextMenu(
    title = category.name,
    items = categoryMenuItems(
        category.id,
        iconValueLabel(category.iconKey, hasImage),
        category.isVisible,
        showsCards = categoryShowsCollections(category),
    ),
    categoryMenuId = category.id,
)

/**
 * The menu a long-press on the bar's [index]th icon opens, or null when there is none to open:
 * no such category, or something already over the bar (an open menu, a category being moved).
 * Y / Triangle never reaches this — on the bar it acts on the focused row.
 */
internal fun categoryLongPressMenu(state: XMBUiState, index: Int): XMBContextMenu? {
    if (state.hasBlockingOverlay) return null
    val category = state.categories.getOrNull(index) ?: return null
    // Built-ins and ids that do not fit the key pattern never carry a device image.
    val hasImage = UserCategoryIconKeys.keyFor(category.id)?.let { it in state.xmbIcons.userKeys } == true
    return categoryContextMenu(category, hasImage)
}

/**
 * What the pad's Select button opens: the current category's menu — the one a long-press on its
 * icon opens — or null when the plain XMB is not what is on screen (anything over the bar, or
 * multi-select), where Select keeps its sort meaning.
 */
internal fun selectButtonCategoryMenu(state: XMBUiState): XMBContextMenu? =
    if (state.markMode) null else categoryLongPressMenu(state, state.selectedCategoryIndex)
