package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.data.repository.PtfThemeImporter
import com.playfieldportal.themekit.PtfIconTint

/** Tray copy for a `.ptf` import: what came across, and how well the icon tint matched. */
internal object PtfImportMessage {

    fun of(result: PtfThemeImporter.Result): String = when (result) {
        is PtfThemeImporter.Result.Success -> buildString {
            append("Imported \"").append(result.themeName).append("\" — ")
            if (result.iconCount == 0) {
                append("wallpaper only")
            } else {
                append("wallpaper and ").append(result.iconCount).append(if (result.iconCount == 1) " icon" else " icons")
                val score = result.tintScore
                if (score != null && score >= PtfIconTint.APPLY_SCORE) append(", tint matched ").append(score).append('%')
            }
        }
        PtfThemeImporter.Result.CxmbNotSupported -> "CXMB (.ctf) themes aren't supported — only official .ptf themes"
        is PtfThemeImporter.Result.Failed -> result.reason
    }
}
