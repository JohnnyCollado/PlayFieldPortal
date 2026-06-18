package com.playfieldportal.feature.themes

import com.playfieldportal.core.data.database.entity.ThemeEntity

/**
 * Built-in themes bundled with the app. These are seeded once by [DatabaseInitializer]
 * and can never be deleted by the user (enforced by ThemeDao.deleteUserTheme which
 * only removes rows where is_built_in = 0).
 */
object BuiltInThemes {

    val CLASSIC_PSP_BLUE = ThemeEntity(
        id               = "builtin_classic_blue",
        name             = "Classic PSP Blue",
        author           = "Play Field Portal",
        version          = "1.0",
        waveColor        = 0xFF0055AAL,
        waveOpacity      = 0.7f,
        waveSpeed        = 1.0f,
        waveAmplitude    = 1.0f,
        accentColor      = 0xFFFFFFFFL,
        textColor        = 0xFFFFFFFFL,
        backgroundUri    = null,
        fontKey          = "system_default",
        hasBootAnimation = false,
        bootAnimationUri = null,
        soundPackUri     = null,
        packagePath      = null,
        isBuiltIn        = true,
        isActive         = true,
    )

    val ALL: List<ThemeEntity> = listOf(CLASSIC_PSP_BLUE)
}
