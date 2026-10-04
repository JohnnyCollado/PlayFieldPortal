package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.ui.icons.UserCategoryIconKeys
import com.playfieldportal.themekit.IconEditorLayout

/** The value of Settings ▸ Themes ▸ Customize XMB Icons, worded like the Theme Studio's Icons rail. */
object CustomIconsRowText {

    /** "None custom" / "N custom": the user's picks among the icons the editor lists ([storedKeys] from CustomIconStore). */
    fun value(storedKeys: Set<String>): String {
        val n = storedKeys.count { IconEditorLayout.tabOf(it) != null || UserCategoryIconKeys.categoryIdFor(it) != null }
        return if (n == 0) "None custom" else "$n custom"
    }
}
