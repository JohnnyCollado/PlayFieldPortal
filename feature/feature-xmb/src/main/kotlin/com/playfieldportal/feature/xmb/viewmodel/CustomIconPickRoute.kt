package com.playfieldportal.feature.xmb.viewmodel

/** Where Pick in the icon editor goes next. */
enum class CustomIconPickRoute { FILE_PICKER, CHOOSER, GRID }

/**
 * The first decision: the source chooser when the applied theme has icons of its own (slot icons or
 * extra PSP bodies), else straight to the file picker, as before the theme source existed.
 */
fun customIconPickRoute(themeSlotIcons: Int, ptfIcons: Int): CustomIconPickRoute =
    if (themeSlotIcons + ptfIcons > 0) CustomIconPickRoute.CHOOSER else CustomIconPickRoute.FILE_PICKER

/**
 * The decision once the theme grid has been loaded. A theme whose files all fail to decode builds no
 * grid; Pick then falls back to the file picker so choosing "From the applied theme" never ends in
 * nothing.
 */
fun themeGridRoute(grid: ThemeIconGridState?): CustomIconPickRoute =
    if (grid != null) CustomIconPickRoute.GRID else CustomIconPickRoute.FILE_PICKER

/**
 * The source chooser Pick raises for [slotKey]. [count] is the applied theme's icon files, named
 * in the first option's detail.
 */
data class SourceChooserState(
    val slotKey: String,
    val slotName: String,
    val themeName: String,
    val count: Int,
) {
    val title: String get() = "Pick an icon for $slotName"

    val message: String get() = "Use one from the applied theme, or an image on your device."

    /** "{theme name} · {n} icons", "1 icon" for a single one. */
    val themeDetail: String get() = "$themeName · " + if (count == 1) "1 icon" else "$count icons"
}

fun sourceChooserFor(slotKey: String, slotName: String, themeName: String, count: Int) =
    SourceChooserState(slotKey, slotName, themeName, count)
