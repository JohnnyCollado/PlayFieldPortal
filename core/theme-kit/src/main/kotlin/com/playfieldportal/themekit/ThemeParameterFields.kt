package com.playfieldportal.themekit

/**
 * The manifest fields that are look parameters: values the Theme Studio authors and the launcher
 * applies to a device setting (accent, icon and text colours, wave, geometry, legibility). Declared
 * once so both sides can be checked against the same list — the launcher's ThemeParameters must
 * apply every one, and the Studio must open and export every one unchanged. A field added here
 * fails both sides' tests until both carry it.
 *
 * Not listed: identity and bookkeeping (name, author, dates, source, schema), and fields that
 * travel with a file rather than a setting (motionCrop rides the motion wallpaper).
 */
object ThemeParameterFields {

    /** Each parameter field's current value in [manifest], keyed by its manifest JSON name. */
    fun valuesOf(manifest: PfpThemeManifest): Map<String, Any?> = mapOf(
        "accentColor" to manifest.accentColor,
        "iconColor" to manifest.iconColor,
        "textColor" to manifest.textColor,
        "subTextColor" to manifest.subTextColor,
        "waveStyle" to manifest.waveStyle,
        "waveStyleV4" to manifest.waveStyleV4,
        "layout" to manifest.layout,
        "textColorExact" to manifest.textColorExact,
        "legibility" to manifest.legibility,
    )

    /** The parameter fields' manifest JSON names. */
    val ALL: Set<String> = valuesOf(PfpThemeManifest(name = "", accentColor = "")).keys
}
