package com.playfieldportal.themekit

/**
 * Platform ids that have dedicated console art, kept in lockstep with the R8-safe static
 * `when` in core-ui's `systemIconRes()` — core-ui's SystemIconsTest is the guard that the
 * list and the `when` never drift apart.
 *
 * Pure JVM here so [CustomizableIcons] (and therefore the v3 codec's `sysicons/` gating)
 * stays buildable by the desktop Theme Studio. The list deliberately EXCLUDES
 * `sysicon_default` (the built-in fallback art) and the UI-identifying entries
 * `favorites`/`settings`/`desktop`: those name content buckets, not platforms a user would
 * recognise as "replace the SNES icon".
 */
val SYSICON_PLATFORM_IDS: List<String> = listOf(
    "allgames",
    "android",
    "atari2600",
    "atari5200",
    "atari7800",
    "atarilynx",
    "c64",
    "dreamcast",
    "gamegear",
    "gb",
    "gba",
    "gbc",
    "gc",
    "mame",
    "mastersystem",
    "megadrive",
    "n3ds",
    "n64",
    "nds",
    "neogeo",
    "nes",
    "ngp",
    "pcengine",
    "ps2",
    "ps3",
    "psp",
    "psvita",
    "psx",
    "saturn",
    "sega32x",
    "segacd",
    "snes",
    "switch",
    "virtualboy",
    "wii",
    "wiiu",
    "windows",
    "wonderswan",
    "wonderswancolor",
    "x360",
)

/**
 * Human label for a console slot in the customizer. Raw platform ids are asset keys
 * (`n3ds`, `segacd`) — editors must never show them verbatim.
 */
fun consoleDisplayName(platformId: String): String = when (platformId) {
    "allgames" -> "All Games"
    "atari2600" -> "Atari 2600"
    "atari5200" -> "Atari 5200"
    "atari7800" -> "Atari 7800"
    "atarilynx" -> "Atari Lynx"
    "c64" -> "Commodore 64"
    "gamegear" -> "Game Gear"
    "gb" -> "Game Boy"
    "gba" -> "Game Boy Advance"
    "gbc" -> "Game Boy Color"
    "gc" -> "GameCube"
    "mame" -> "Arcade (MAME)"
    "mastersystem" -> "Master System"
    "megadrive" -> "Mega Drive"
    "n3ds" -> "Nintendo 3DS"
    "n64" -> "Nintendo 64"
    "nds" -> "Nintendo DS"
    "neogeo" -> "Neo Geo"
    "nes" -> "NES"
    "ngp" -> "Neo Geo Pocket"
    "pcengine" -> "PC Engine"
    "ps2" -> "PlayStation 2"
    "ps3" -> "PlayStation 3"
    "psp" -> "PSP"
    "psvita" -> "PS Vita"
    "psx" -> "PlayStation"
    "sega32x" -> "Mega Drive 32X"
    "segacd" -> "Sega CD"
    "snes" -> "SNES"
    "virtualboy" -> "Virtual Boy"
    "wonderswan" -> "WonderSwan"
    "wonderswancolor" -> "WonderSwan Color"
    "x360" -> "Xbox 360"
    else -> platformId.uppercase()
}

/**
 * The superset registry the icon customizer edits and the v3 codec gates on: every theme
 * slot plus the console icons, under forever-stable `sysicon_<platformId>` keys.
 *
 * `IconSlots.ALL` is the bundle contract (keys are zip entry names) and its KDoc states
 * console art is deliberately not a slot — so this registry EXTENDS it without touching it:
 * [ALL] keeps `IconSlots.ALL` as a verbatim prefix, and the codec still gates `icons/`
 * entries on `IconSlots.isValidKey` while `sysicons/` entries gate on [isValidKey]'s
 * console arm. Slot keys are used verbatim as file names, which makes [isValidKey]
 * load-bearing: it is what stops a crafted key escaping its directory.
 */
object CustomizableIcons {

    /** Template size for console art, matching the catbar/item templates. */
    private const val CONSOLE_TEMPLATE_PX = 256

    val ALL: List<IconSlot> = IconSlots.ALL + SYSICON_PLATFORM_IDS.map { id ->
        IconSlot(
            key = "sysicon_$id",
            group = IconSlot.Group.CONSOLE,
            displayName = consoleDisplayName(id),
            templateSizePx = CONSOLE_TEMPLATE_PX,
        )
    }

    private val byKey: Map<String, IconSlot> = ALL.associateBy { it.key }

    fun byKey(key: String): IconSlot? = byKey[key]

    fun isValidKey(key: String): Boolean = key in byKey

    fun group(group: IconSlot.Group): List<IconSlot> = ALL.filter { it.group == group }
}
