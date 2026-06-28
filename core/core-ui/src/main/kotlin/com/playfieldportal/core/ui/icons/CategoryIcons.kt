package com.playfieldportal.core.ui.icons

import androidx.annotation.DrawableRes
import com.playfieldportal.core.ui.R

/** A category icon a user can pick, backed by an individual drawable (no sprite sheet, no Canvas).
 *  XMB column glyphs use the `catbar_*` art; console icons use the `sysicon_*` art — both from the
 *  xmb-menu-es-de theme. */
data class CategoryIcon(
    val key: String,
    val label: String,
    @DrawableRes val resId: Int,
)

/** Every icon a user can pick for a category, in picker order: the XMB column glyphs and the
 *  Favorites glyph first, then every bundled console icon. Single source of truth — the Settings
 *  picker and the XMB bar both resolve icons through [categoryIconFor]. */
val CATEGORY_ICON_CATALOG: List<CategoryIcon> = listOf(
    // ── XMB column glyphs ────────────────────────────────────────────────────────
    CategoryIcon("ic_settings",  "Settings",  R.drawable.catbar_settings),
    CategoryIcon("ic_photos",    "Photos",    R.drawable.catbar_photos),
    CategoryIcon("ic_music",     "Music",     R.drawable.catbar_music),
    CategoryIcon("ic_videos",    "Video",     R.drawable.catbar_video),
    CategoryIcon("ic_games",     "Games",     R.drawable.catbar_games),
    CategoryIcon("ic_network",   "Network",   R.drawable.catbar_network),
    CategoryIcon("ic_appstore",  "App Store", R.drawable.catbar_appstore),
    CategoryIcon("ic_favorites", "Favorites", R.drawable.catbar_favorites),
    // ── Console icons (sysicon_* art, also used for memory-card media) ────────────
    CategoryIcon("ic_nes",            "NES",                  R.drawable.sysicon_nes),
    CategoryIcon("ic_snes",           "Super NES",            R.drawable.sysicon_snes),
    CategoryIcon("ic_n64",            "Nintendo 64",          R.drawable.sysicon_n64),
    CategoryIcon("ic_gc",             "GameCube",             R.drawable.sysicon_gc),
    CategoryIcon("ic_wii",            "Wii",                  R.drawable.sysicon_wii),
    CategoryIcon("ic_wiiu",           "Wii U",                R.drawable.sysicon_wiiu),
    CategoryIcon("ic_switch",         "Switch",               R.drawable.sysicon_switch),
    CategoryIcon("ic_gb",             "Game Boy",             R.drawable.sysicon_gb),
    CategoryIcon("ic_gbc",            "Game Boy Color",       R.drawable.sysicon_gbc),
    CategoryIcon("ic_gba",            "Game Boy Advance",     R.drawable.sysicon_gba),
    CategoryIcon("ic_nds",            "Nintendo DS",          R.drawable.sysicon_nds),
    CategoryIcon("ic_n3ds",           "Nintendo 3DS",         R.drawable.sysicon_n3ds),
    CategoryIcon("ic_virtualboy",     "Virtual Boy",          R.drawable.sysicon_virtualboy),
    CategoryIcon("ic_psx",            "PlayStation",          R.drawable.sysicon_psx),
    CategoryIcon("ic_ps2",            "PlayStation 2",        R.drawable.sysicon_ps2),
    CategoryIcon("ic_ps3",            "PlayStation 3",        R.drawable.sysicon_ps3),
    CategoryIcon("ic_psp",            "PSP",                  R.drawable.sysicon_psp),
    CategoryIcon("ic_psvita",         "PS Vita",              R.drawable.sysicon_psvita),
    CategoryIcon("ic_mastersystem",   "Master System",        R.drawable.sysicon_mastersystem),
    CategoryIcon("ic_megadrive",      "Genesis / Mega Drive", R.drawable.sysicon_megadrive),
    CategoryIcon("ic_sega32x",        "Sega 32X",             R.drawable.sysicon_sega32x),
    CategoryIcon("ic_segacd",         "Sega CD",              R.drawable.sysicon_segacd),
    CategoryIcon("ic_saturn",         "Saturn",               R.drawable.sysicon_saturn),
    CategoryIcon("ic_dreamcast",      "Dreamcast",            R.drawable.sysicon_dreamcast),
    CategoryIcon("ic_gamegear",       "Game Gear",            R.drawable.sysicon_gamegear),
    CategoryIcon("ic_pcengine",       "PC Engine / TG-16",    R.drawable.sysicon_pcengine),
    CategoryIcon("ic_neogeo",         "Neo Geo",              R.drawable.sysicon_neogeo),
    CategoryIcon("ic_ngp",            "Neo Geo Pocket",       R.drawable.sysicon_ngp),
    CategoryIcon("ic_wonderswan",     "WonderSwan",           R.drawable.sysicon_wonderswan),
    CategoryIcon("ic_wonderswancolor", "WonderSwan Color",    R.drawable.sysicon_wonderswancolor),
    CategoryIcon("ic_atari2600",      "Atari 2600",           R.drawable.sysicon_atari2600),
    CategoryIcon("ic_atari5200",      "Atari 5200",           R.drawable.sysicon_atari5200),
    CategoryIcon("ic_atari7800",      "Atari 7800",           R.drawable.sysicon_atari7800),
    CategoryIcon("ic_atarilynx",      "Atari Lynx",           R.drawable.sysicon_atarilynx),
    CategoryIcon("ic_c64",            "Commodore 64",         R.drawable.sysicon_c64),
    CategoryIcon("ic_mame",           "Arcade (MAME)",        R.drawable.sysicon_mame),
    CategoryIcon("ic_windows",        "Windows / PC",         R.drawable.sysicon_windows),
    CategoryIcon("ic_xbox360",        "Xbox 360",             R.drawable.sysicon_x360),
    CategoryIcon("ic_android",        "Android",              R.drawable.sysicon_android),
)

private val CATALOG_BY_KEY: Map<String, CategoryIcon> = CATEGORY_ICON_CATALOG.associateBy { it.key }

/** Legacy iconKeys saved by older builds (the sprite-sheet picker) mapped onto current catalog
 *  keys, so categories created before this change still resolve to the right art. */
private val LEGACY_ALIASES: Map<String, String> = mapOf(
    "ic_arcade"      to "ic_mame",
    "ic_pc"          to "ic_windows",
    "ic_genesis"     to "ic_megadrive",
    "ic_gameboy"     to "ic_gb",
    "ic_ds"          to "ic_nds",
    "ic_vita"        to "ic_psvita",
    "ic_ps1"         to "ic_psx",
    "ic_ps_generic"  to "ic_ps2",
    "ic_gamecube"    to "ic_gc",
    "ic_turbografx"  to "ic_pcengine",
    "ic_sega_cd"     to "ic_segacd",
    "ic_atari"       to "ic_atari2600",
    "ic_xbox"        to "ic_xbox360",
)

/** The games glyph — used whenever an iconKey can't be resolved. */
val FALLBACK_CATEGORY_ICON: CategoryIcon = CATALOG_BY_KEY.getValue("ic_games")

/** Resolves any stored iconKey (current or legacy) to a catalog icon, falling back to the
 *  games glyph for unknown keys. */
fun categoryIconFor(iconKey: String): CategoryIcon =
    CATALOG_BY_KEY[iconKey]
        ?: LEGACY_ALIASES[iconKey]?.let { CATALOG_BY_KEY[it] }
        ?: FALLBACK_CATEGORY_ICON
