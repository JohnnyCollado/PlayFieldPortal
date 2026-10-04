package com.playfieldportal.feature.xmb.ui

import androidx.annotation.DrawableRes
import com.playfieldportal.feature.xmb.R
import com.playfieldportal.themekit.PhysicalMediaIds

/**
 * The PNG filename (without extension) under assets/systems/physical-media/ for [platformId] —
 * regional art included — or null for digital-only platforms. One table with the slot below:
 * [PhysicalMediaIds], which the Theme Studio reads too.
 */
fun physicalMediaAssetName(platformId: String?): String? = PhysicalMediaIds.artFile(platformId)

/**
 * Fallback generic vector drawable used when the PNG asset is absent.
 * Approximates the correct media shape for the platform.
 */
@DrawableRes
fun physicalMediaIconRes(platformId: String?): Int? = when (platformId) {

    "psx", "ps1",
    "ps2", "ps3",
    "saturn", "segacd", "sega32x",
    "dreamcast", "dc", "naomi", "atomiswave",
    "wii", "wiiu",
    "windows",
    "xbox", "x360", "xbox360"       -> R.drawable.media_disc

    "psp"                           -> R.drawable.media_umd
    "psvita"                        -> R.drawable.media_cartridge_vita
    "gc", "gamecube"                -> R.drawable.media_disc_mini
    "switch", "nx"                  -> R.drawable.media_cartridge_switch

    "nds", "ds",
    "n3ds", "3ds"                   -> R.drawable.media_cartridge_ds

    "gb", "gbc", "gba",
    "virtualboy", "vb"              -> R.drawable.media_cartridge_gb

    "nes", "fam", "famicom",
    "snes", "sfc", "n64",
    "genesis", "megadrive", "md",
    "mastersystem", "sms",
    "atari2600", "atari5200", "atari7800",
    "neogeo", "3do"                 -> R.drawable.media_cartridge

    "gamegear", "ngp", "ngpc",
    "atarilynx", "lynx",
    "wonderswan", "ws",
    "wonderswancolor", "wsc"        -> R.drawable.media_cartridge_gb

    "pcengine", "pce", "tgfx16"    -> R.drawable.media_hucard
    "c64", "amiga", "msx"          -> R.drawable.media_floppy

    "android", "steam",
    "gog", "arcade", "mame",
    "default"                       -> null

    else                            -> null
}

/**
 * The `physmedia_<id>` slot a user pick or the applied theme replaces [platformId]'s media art
 * through, or null for a platform with no themeable media (digital-only or unknown).
 */
fun physicalMediaSlotKey(platformId: String?): String? = PhysicalMediaIds.slotKey(platformId)
