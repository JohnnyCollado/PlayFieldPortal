package com.playfieldportal.feature.launcher

import timber.log.Timber
import java.io.File

internal data class RetroArchCore(
    val name: String,           // human-readable label derived from filename
    val fileName: String,       // e.g. "nestopia_libretro_android.so"
    val absolutePath: String,
    val platformIds: List<String>,
)

internal object RetroArchCoreScanner {

    val RETROARCH_PACKAGES = listOf(
        "com.retroarch.aarch64",
        "com.retroarch.ra64",
        "com.retroarch",
        "com.retroarch.ra32",
    )

    private val CORE_DIRS = listOf(
        "/storage/emulated/0/RetroArch/cores",
        "/sdcard/RetroArch/cores",
    )

    // Core filename prefix (strip _libretro_android.so) → platform IDs
    private val CORE_PLATFORM_MAP: Map<String, List<String>> = mapOf(
        "nestopia"                    to listOf("nes", "fam"),
        "mesen"                       to listOf("nes", "fam"),
        "fceumm"                      to listOf("nes", "fam"),
        "snes9x"                      to listOf("snes"),
        "bsnes"                       to listOf("snes"),
        "bsnes_mercury_accuracy"      to listOf("snes"),
        "bsnes_mercury_balanced"      to listOf("snes"),
        "mesen-s"                     to listOf("snes"),
        "genesis_plus_gx"             to listOf("genesis", "megadrive", "sms", "gamegear"),
        "genesis_plus_gx_wide"        to listOf("genesis", "megadrive"),
        "picodrive"                   to listOf("genesis", "megadrive", "sms", "gamegear"),
        "gambatte"                    to listOf("gb", "gbc"),
        "mgba"                        to listOf("gb", "gbc", "gba"),
        "vba_next"                    to listOf("gba"),
        "vbam"                        to listOf("gba", "gb", "gbc"),
        "mupen64plus_next"            to listOf("n64"),
        "parallel_n64"                to listOf("n64"),
        "swanstation"                 to listOf("psx", "ps1"),
        "pcsx_rearmed"                to listOf("psx", "ps1"),
        "mednafen_psx"                to listOf("psx", "ps1"),
        "mednafen_psx_hw"             to listOf("psx", "ps1"),
        "pcsx2"                       to listOf("ps2"),
        "ppsspp"                      to listOf("psp"),
        "kronos"                      to listOf("saturn"),
        "desmume"                     to listOf("nds", "ds"),
        "melonds"                     to listOf("nds", "ds"),
        "dolphin"                     to listOf("gc", "gamecube", "wii"),
        "citra"                       to listOf("3ds", "n3ds"),
        "mame2003_plus"               to listOf("arcade"),
        "mame2003"                    to listOf("arcade"),
        "mame"                        to listOf("arcade"),
        "fbneo"                       to listOf("arcade", "neogeo"),
        "mednafen_pce"                to listOf("pce", "pcengine", "tgfx16"),
        "mednafen_pce_fast"           to listOf("pce", "pcengine", "tgfx16"),
        "mednafen_saturn"             to listOf("saturn"),
        "yabause"                     to listOf("saturn"),
        "yabasanshiro"                to listOf("saturn"),
        "stella"                      to listOf("atari2600"),
        "prosystem"                   to listOf("atari7800"),
        "smsplus"                     to listOf("sms", "mastersystem", "gamegear"),
        "bluemsx"                     to listOf("msx"),
        "fmsx"                        to listOf("msx"),
        "puae"                        to listOf("amiga"),
        "vice_x64"                    to listOf("c64"),
        "vice_x128"                   to listOf("c64"),
        "mednafen_lynx"               to listOf("lynx", "atarilynx"),
        "handy"                       to listOf("lynx", "atarilynx"),
        "mednafen_vb"                 to listOf("vb", "virtualboy"),
        "mednafen_wswan"              to listOf("ws", "wsc", "wonderswan", "wonderswancolor"),
        "race"                        to listOf("ngp", "ngpc"),
        "mednafen_ngp"                to listOf("ngp", "ngpc"),
        "opera"                       to listOf("3do"),
        "flycast"                     to listOf("dreamcast", "dc", "naomi", "atomiswave"),
    )

    fun scan(): List<RetroArchCore> {
        val coresDir = CORE_DIRS.map(::File).firstOrNull { it.isDirectory } ?: run {
            Timber.d("RetroArch cores directory not found")
            return emptyList()
        }
        return try {
            coresDir.listFiles()
                ?.filter { it.isFile && it.name.contains("_libretro") && it.extension == "so" }
                ?.mapNotNull { file ->
                    val prefix = file.name
                        .removeSuffix("_libretro_android.so")
                        .removeSuffix("_libretro.so")
                    val platforms = CORE_PLATFORM_MAP[prefix] ?: return@mapNotNull null
                    RetroArchCore(
                        name         = prefix.replace('_', ' ').replaceFirstChar { it.uppercaseChar() },
                        fileName     = file.name,
                        absolutePath = file.absolutePath,
                        platformIds  = platforms,
                    )
                }
                ?: emptyList()
        } catch (e: Exception) {
            Timber.w(e, "RetroArch core scan failed")
            emptyList()
        }
    }
}
