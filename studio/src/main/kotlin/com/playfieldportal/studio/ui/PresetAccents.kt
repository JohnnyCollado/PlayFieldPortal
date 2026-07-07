package com.playfieldportal.studio.ui

/**
 * The 12 preset accent swatches — the launcher's XmbColorScheme wave colors (11 fixed
 * presets + the "Original" January teal). Values mirror core-domain XmbColorScheme.resolve;
 * they can't be imported here because that enum lives in an Android module.
 */
data class PresetAccent(val name: String, val argb: Int)

val PRESET_ACCENTS: List<PresetAccent> = listOf(
    PresetAccent("Original", 0xFF1FA89C.toInt()),
    PresetAccent("Classic Blue", 0xFF0055AA.toInt()),
    PresetAccent("Sunset Orange", 0xFFFF8A3D.toInt()),
    PresetAccent("Fresh Green", 0xFF36C26B.toInt()),
    PresetAccent("Royal Purple", 0xFF7A4DD6.toInt()),
    PresetAccent("Crimson Red", 0xFFE03B4F.toInt()),
    PresetAccent("Silver", 0xFFB8C4D0.toInt()),
    PresetAccent("Sakura Pink", 0xFFE87FB0.toInt()),
    PresetAccent("Golden Amber", 0xFFE0A32E.toInt()),
    PresetAccent("Aqua Teal", 0xFF2EC4B6.toInt()),
    PresetAccent("Midnight Navy", 0xFF23477E.toInt()),
    PresetAccent("Charcoal", 0xFF4A505A.toInt()),
)
