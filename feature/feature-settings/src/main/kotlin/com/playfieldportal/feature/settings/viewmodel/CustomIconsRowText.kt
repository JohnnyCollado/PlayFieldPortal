package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.core.ui.icons.UserCategoryIconKeys
import com.playfieldportal.feature.settings.R
import com.playfieldportal.themekit.IconEditorLayout

/** The value of Settings ▸ Themes ▸ Customize XMB Icons, worded like the Theme Studio's Icons rail. */
object CustomIconsRowText {

    /** "None custom" / "N custom": the user's picks among the icons the editor lists ([storedKeys] from CustomIconStore). */
    fun value(storedKeys: Set<String>): UiText {
        val n = storedKeys.count { IconEditorLayout.tabOf(it) != null || UserCategoryIconKeys.categoryIdFor(it) != null }
        return if (n == 0) UiText.res(R.string.setd_custom_icons_none) else UiText.res(R.string.setd_custom_icons_count, n)
    }
}
