package com.playfieldportal.themekit

// Test-side views of PfpThemeBundle.icons by the folder each family travels in, keyed the way the
// zip names them: theme slots by slot key, console and physical-media art by platform id. The
// bundle itself holds one slot-keyed map; only the codec maps keys to folders.

/** The theme-slot icons (`icons/<key>`). */
val PfpThemeBundle.slotIcons: Map<String, ThemeImage>
    get() = icons.filterKeys { IconSlots.isValidKey(it) }

/** The console art (`sysicons/<id>`), keyed by platform id. */
val PfpThemeBundle.consoleArt: Map<String, ThemeImage>
    get() = icons.filterKeys { CustomizableIcons.byKey(it)?.group == IconSlot.Group.CONSOLE }
        .mapKeys { (key, _) -> key.removePrefix(CustomizableIcons.SYSICON_PREFIX) }

/** The physical-media art (`mediaicons/<id>`), keyed by platform id. */
val PfpThemeBundle.mediaArt: Map<String, ThemeImage>
    get() = icons.mapNotNull { (key, image) -> CustomizableIcons.physicalMediaId(key)?.let { it to image } }.toMap()

/** Console art for a bundle under construction: platform id → image, as slot keys. */
fun consoleArt(vararg entries: Pair<String, ThemeImage>): Map<String, ThemeImage> =
    entries.associate { (id, image) -> "${CustomizableIcons.SYSICON_PREFIX}$id" to image }

/** Physical-media art for a bundle under construction: platform id → image, as slot keys. */
fun mediaArt(vararg entries: Pair<String, ThemeImage>): Map<String, ThemeImage> =
    entries.associate { (id, image) -> "${CustomizableIcons.PHYSICAL_MEDIA_PREFIX}$id" to image }
