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
 * recognise as "replace the SNES icon". Those, plus the CPS/Xbox ids, live in
 * [SYSICON_EXTRA_IDS] instead.
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
 * Console slots a theme may carry art for that are not platform-list ids (A4): arcade CPS
 * boards, Xbox, the UI buckets, and the `default` fallback art. Appended after
 * [SYSICON_PLATFORM_IDS] in [CustomizableIcons.ALL]. Slots only — no bundled art was added, so
 * core-ui's `systemIconRes()` and its guard test are deliberately untouched.
 */
val SYSICON_EXTRA_IDS: List<String> = listOf(
    "cps1",
    "cps2",
    "cps3",
    "xbox",
    "favorites",
    "desktop",
    "default",
)

/**
 * Human label for a console slot in the customizer. Raw platform ids are asset keys
 * (`n3ds`, `segacd`) — editors must never show them verbatim.
 */
fun consoleDisplayName(platformId: String): String = when (platformId) {
    "allgames" -> "All Games"
    "android" -> "Android"
    "dreamcast" -> "Dreamcast"
    "saturn" -> "Saturn"
    "switch" -> "Switch"
    "wii" -> "Wii"
    "wiiu" -> "Wii U"
    "windows" -> "Windows"
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
    "cps1" -> "Capcom CPS-1"
    "cps2" -> "Capcom CPS-2"
    "cps3" -> "Capcom CPS-3"
    "xbox" -> "Xbox"
    "favorites" -> "Favorites"
    "desktop" -> "Desktop"
    "default" -> "Default Console"
    else -> platformId.uppercase()
}

/** Console ids with no physical media: the storefront/UI buckets and the generic fallback. */
private val NO_PHYSICAL_MEDIA_IDS = setOf("allgames", "android", "favorites", "desktop", "default")

/**
 * The superset registry the icon customizer edits and the v3 codec gates on: every theme
 * slot plus the console icons, under forever-stable `sysicon_<platformId>` keys, then the
 * physical-media art (the disc, cart or UMD Physical Media mode draws) under `physmedia_<platformId>`.
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

    /** Prefix of the console-art keys; the rest of the key is the platform id. */
    const val SYSICON_PREFIX = "sysicon_"

    /** Prefix of the physical-media keys; the rest of the key is the platform id. */
    const val PHYSICAL_MEDIA_PREFIX = "physmedia_"

    private val CONSOLE_IDS = SYSICON_PLATFORM_IDS + SYSICON_EXTRA_IDS

    val ALL: List<IconSlot> = IconSlots.ALL +
        CONSOLE_IDS.map { id ->
            IconSlot(
                key = "$SYSICON_PREFIX$id",
                group = IconSlot.Group.CONSOLE,
                displayName = consoleDisplayName(id),
                templateSizePx = CONSOLE_TEMPLATE_PX,
            )
        } +
        CONSOLE_IDS.filter { it !in NO_PHYSICAL_MEDIA_IDS }.map { id ->
            IconSlot(
                key = "$PHYSICAL_MEDIA_PREFIX$id",
                group = IconSlot.Group.PHYSICAL_MEDIA,
                displayName = consoleDisplayName(id),
                templateSizePx = CONSOLE_TEMPLATE_PX,
            )
        }

    private val byKey: Map<String, IconSlot> = ALL.associateBy { it.key }

    /** The platform id behind a registered `physmedia_` key, or null for any other key. */
    fun physicalMediaId(key: String): String? =
        key.takeIf { it.startsWith(PHYSICAL_MEDIA_PREFIX) && isValidKey(it) }?.removePrefix(PHYSICAL_MEDIA_PREFIX)

    fun byKey(key: String): IconSlot? = byKey[key]

    fun isValidKey(key: String): Boolean = key in byKey

    fun group(group: IconSlot.Group): List<IconSlot> = ALL.filter { it.group == group }
}
