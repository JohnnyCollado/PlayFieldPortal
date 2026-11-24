package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.feature.settings.R
import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.core.data.repository.PtfThemeImporter
import com.playfieldportal.themekit.PtfIconTint

/** Tray copy for a `.ptf` import: what came across, and how well the icon tint matched. */
internal object PtfImportMessage {

    fun of(result: PtfThemeImporter.Result): UiText = when (result) {
        is PtfThemeImporter.Result.Success -> {
            val score = result.tintScore
            when {
                result.iconCount == 0 -> UiText.res(R.string.sets_ptf_wallpaper_only, result.themeName)
                score != null && score >= PtfIconTint.APPLY_SCORE ->
                    UiText.plural(R.plurals.sets_ptf_icons_tint, result.iconCount, result.themeName, result.iconCount, score)
                else -> UiText.plural(R.plurals.sets_ptf_icons, result.iconCount, result.themeName, result.iconCount)
            }
        }
        PtfThemeImporter.Result.CxmbNotSupported -> UiText.res(R.string.sets_ptf_cxmb_unsupported)
        is PtfThemeImporter.Result.Failed -> UiText.raw(result.reason)
    }
}
