package com.playfieldportal.feature.xmb.ui

import androidx.annotation.DrawableRes
import com.playfieldportal.feature.xmb.R

/**
 * Resolves a platform ID to the PNG filename (without extension) under
 * assets/systems/physical-media/.  Returns the platformId unchanged when it
 * already has a matching file; maps known aliases to the file that exists.
 * Returns null for digital-only platforms that have no physical media.
 */
fun physicalMediaAssetName(platformId: String?): String? = when (platformId) {

    // ── Aliases that differ from the PNG filename ──────────────────────────────
    "ps1"                            -> "psx"
    "fam", "famicom"                 -> "nes"
    "ds"                             -> "nds"
    "3ds"                            -> "n3ds"
    "nx"                             -> "switch"
    "gamecube"                       -> "gc"
    "md"                             -> "megadrive"
    "sms"                            -> "mastersystem"
    "dc"                             -> "dreamcast"
    "naomi", "atomiswave"            -> "arcade"
    "pce"                            -> "pcengine"
    "tgfx16"                         -> "tg16"
    "lynx"                           -> "atarilynx"
    "vb"                             -> "virtualboy"
    "ws"                             -> "wonderswan"
    "wsc"                            -> "wonderswancolor"

    // ── Digital-only — no physical media ──────────────────────────────────────
    "android", "windows", "steam",
    "gog", "default"                 -> null

    // ── Everything else: platformId == filename ────────────────────────────────
    else                             -> platformId
}

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
    "wii", "wiiu"                   -> R.drawable.media_disc

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

    "android", "windows", "steam",
    "gog", "arcade", "mame",
    "default"                       -> null

    else                            -> null
}
