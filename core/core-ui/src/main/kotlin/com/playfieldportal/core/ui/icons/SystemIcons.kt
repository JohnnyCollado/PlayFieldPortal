package com.playfieldportal.core.ui.icons

import androidx.annotation.DrawableRes
import com.playfieldportal.core.ui.R

/**
 * Maps a platform id to its bundled per-console icon (the xmb-menu-es-de set), falling back to
 * the generic [R.drawable.sysicon_default] for platforms with no dedicated art.
 *
 * This is a static `when` rather than a `Resources.getIdentifier("sysicon_$id", ...)` lookup on
 * purpose. A name-based lookup is invisible to R8, so every drawable here would look unreferenced
 * and be stripped by resource shrinking — which is why the project used to have to disable
 * `android.r8.optimizedResourceShrinking`. Naming each id keeps the shrinker correct by default.
 *
 * Keep this in sync with `core-ui/src/main/res/drawable-nodpi/sysicon_*.png`.
 */
@DrawableRes
fun systemIconRes(platformId: String?): Int = when (platformId?.lowercase()) {
    "allgames" -> R.drawable.sysicon_allgames
    "android" -> R.drawable.sysicon_android
    "atari2600" -> R.drawable.sysicon_atari2600
    "atari5200" -> R.drawable.sysicon_atari5200
    "atari7800" -> R.drawable.sysicon_atari7800
    "atarilynx" -> R.drawable.sysicon_atarilynx
    "c64" -> R.drawable.sysicon_c64
    "desktop" -> R.drawable.sysicon_desktop
    "dreamcast" -> R.drawable.sysicon_dreamcast
    "favorites" -> R.drawable.sysicon_favorites
    "gamegear" -> R.drawable.sysicon_gamegear
    "gb" -> R.drawable.sysicon_gb
    "gba" -> R.drawable.sysicon_gba
    "gbc" -> R.drawable.sysicon_gbc
    "gc" -> R.drawable.sysicon_gc
    "mame" -> R.drawable.sysicon_mame
    "mastersystem" -> R.drawable.sysicon_mastersystem
    "megadrive" -> R.drawable.sysicon_megadrive
    "n3ds" -> R.drawable.sysicon_n3ds
    "n64" -> R.drawable.sysicon_n64
    "nds" -> R.drawable.sysicon_nds
    "neogeo" -> R.drawable.sysicon_neogeo
    "nes" -> R.drawable.sysicon_nes
    "ngp" -> R.drawable.sysicon_ngp
    "pcengine" -> R.drawable.sysicon_pcengine
    "ps2" -> R.drawable.sysicon_ps2
    "ps3" -> R.drawable.sysicon_ps3
    "psp" -> R.drawable.sysicon_psp
    "psvita" -> R.drawable.sysicon_psvita
    "psx" -> R.drawable.sysicon_psx
    "saturn" -> R.drawable.sysicon_saturn
    "sega32x" -> R.drawable.sysicon_sega32x
    "segacd" -> R.drawable.sysicon_segacd
    "settings" -> R.drawable.sysicon_settings
    "snes" -> R.drawable.sysicon_snes
    "switch" -> R.drawable.sysicon_switch
    "virtualboy" -> R.drawable.sysicon_virtualboy
    "wii" -> R.drawable.sysicon_wii
    "wiiu" -> R.drawable.sysicon_wiiu
    "windows" -> R.drawable.sysicon_windows
    "wonderswan" -> R.drawable.sysicon_wonderswan
    "wonderswancolor" -> R.drawable.sysicon_wonderswancolor
    "x360" -> R.drawable.sysicon_x360
    else -> R.drawable.sysicon_default
}
