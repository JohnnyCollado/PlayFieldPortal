package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.ui.icons.UserCategoryIconKeys

/**
 * A user-created category offered as a Category Bar slot in Customize XMB Icons. [key] is the
 * store key (`usercat_<id>`); [iconKey] is the catalog glyph the bar draws until an image is
 * picked. Not an `IconSlot` on purpose: these are device-local and never part of a theme.
 */
data class UserCategoryIconSlot(
    val key: String,
    val categoryId: String,
    val displayName: String,
    val iconKey: String,
)

/**
 * The slots for [categories] (the bar, in bar order): visible user-created categories only.
 * Built-ins and ids with no valid store key are skipped.
 */
fun userCategoryIconSlots(categories: List<Category>): List<UserCategoryIconSlot> =
    categories.mapNotNull { category ->
        if (!category.isVisible) return@mapNotNull null
        val key = UserCategoryIconKeys.keyFor(category.id) ?: return@mapNotNull null
        UserCategoryIconSlot(key, category.id, category.name, category.iconKey)
    }
