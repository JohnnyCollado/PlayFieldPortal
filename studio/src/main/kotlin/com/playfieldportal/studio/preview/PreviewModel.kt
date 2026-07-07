package com.playfieldportal.studio.preview

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.playfieldportal.studio.IconColorChoice
import com.playfieldportal.studio.StudioState
import com.playfieldportal.themekit.ColorCascade
import com.playfieldportal.themekit.PfpThemeManifest

/** Everything the preview canvas needs, resolved from [StudioState]. */
data class XmbPreviewModel(
    val accent: Color,
    val iconTint: Color,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val wallpaper: ImageBitmap?,
    val reducedWave: Boolean,
    /** IconSlots key → custom bitmap; slots not present draw the built-in glyph. */
    val iconOverrides: Map<String, ImageBitmap>,
)

fun StudioState.toPreviewModel(): XmbPreviewModel {
    val accentLong = 0xFF000000L or (accentArgb.toLong() and 0xFFFFFF)
    val (top, bottom) = ColorCascade.lightBackgroundAnchors(accentLong)
    return XmbPreviewModel(
        accent = Color(accentArgb),
        // "Auto" has no derivation in the launcher yet either — both render white until
        // derive-from-accent lands (PfpThemeManifest.ICON_COLOR_AUTO contract).
        iconTint = when (val c = iconColor) {
            IconColorChoice.Auto -> Color.White
            is IconColorChoice.Custom -> Color(c.argb)
        },
        backgroundTop = Color(top.toInt()),
        backgroundBottom = Color(bottom.toInt()),
        wallpaper = wallpaperBitmap,
        reducedWave = waveStyle == PfpThemeManifest.WAVE_REDUCED,
        iconOverrides = iconBitmaps,
    )
}

/**
 * The representative frame the preview shows: the Video category selected, because its
 * item rows exercise themeable item slots (folders, library, recents...) rather than the
 * non-themeable platform icons the Games column leads with.
 */
object SampleContent {

    data class Category(val slotKey: String, val label: String)
    data class Row(val slotKey: String, val title: String, val subtitle: String? = null)

    val categories: List<Category> = listOf(
        Category("catbar_settings", "Settings"),
        Category("catbar_photos", "Photo"),
        Category("catbar_music", "Music"),
        Category("catbar_video", "Video"),
        Category("catbar_games", "Game"),
        Category("catbar_network", "Network"),
        Category("catbar_appstore", "App Store"),
        Category("catbar_social", "Social"),
        Category("catbar_favorites", "Favorites"),
    )

    const val SELECTED_CATEGORY = 3 // Video

    val rows: List<Row> = listOf(
        Row("item_video_apps", "Video Apps"),
        Row("item_video_library", "All Videos", "132 videos"),
        Row("item_video_recent", "Recently Watched"),
        Row("item_video_favorites", "Favorites"),
        Row("item_video_collections", "Collections"),
    )

    const val SELECTED_ROW = 1
}
