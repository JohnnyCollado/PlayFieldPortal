package com.playfieldportal.core.data.platform

import javax.inject.Inject
import javax.inject.Singleton

// Detects platform from folder names in a ROM file path.
// Used to resolve ambiguous extensions (.iso, .chd, .bin, .cue, .img)
// that can belong to multiple platforms based on how the user organised their library.
//
// Folder name priority: deepest (most-specific) path segment wins.
// e.g. /ROMs/PS2/action/game.iso → "ps2" from segment "ps2"
//
// Primary folder names follow EmulationStation Desktop Edition (ES-DE) conventions
// so that a standard ES-DE roms/ layout is detected automatically without any
// manual configuration.
@Singleton
class PlatformFolderHintResolver @Inject constructor() {

    // Key  = lowercase folder segment (exact match)
    // Value = platform ID matching PlatformSeeder
    private val hints: Map<String, String> = mapOf(

        // ── Sony — ES-DE canonical first, then common aliases ──────────────

        // PSX / PlayStation 1
        "psx"                       to "psx",   // ES-DE canonical
        "ps1"                       to "psx",
        "playstation"               to "psx",
        "playstation1"              to "psx",
        "playstation 1"             to "psx",
        "sony playstation"          to "psx",
        "pcsxr"                     to "psx",

        // PS2
        "ps2"                       to "ps2",   // ES-DE canonical
        "playstation2"              to "ps2",
        "playstation 2"             to "ps2",
        "pcsx2"                     to "ps2",
        "aethersx2"                 to "ps2",

        // PS3
        "ps3"                       to "ps3",   // ES-DE canonical
        "playstation3"              to "ps3",
        "playstation 3"             to "ps3",
        "rpcs3"                     to "ps3",

        // PSP
        "psp"                       to "psp",   // ES-DE canonical
        "playstation portable"      to "psp",
        "ppsspp"                    to "psp",
        "ppsspp game"               to "psp",
        "game"                      to "psp",   // PPSSPP default: /PSP/GAME/

        // PS Vita
        "psvita"                    to "psvita", // ES-DE canonical
        "vita"                      to "psvita",
        "ps vita"                   to "psvita",
        "playstation vita"          to "psvita",

        // ── Nintendo — ES-DE canonical first ──────────────────────────────

        // NES
        "nes"                       to "nes",    // ES-DE canonical
        "famicom"                   to "nes",
        "nintendo entertainment system" to "nes",

        // SNES
        "snes"                      to "snes",   // ES-DE canonical
        "super nintendo"            to "snes",
        "super famicom"             to "snes",
        "superfamicom"              to "snes",
        "sfc"                       to "snes",

        // N64
        "n64"                       to "n64",    // ES-DE canonical
        "nintendo 64"               to "n64",
        "nintendo64"                to "n64",

        // Game Boy
        "gb"                        to "gb",     // ES-DE canonical
        "gameboy"                   to "gb",
        "game boy"                  to "gb",
        "game boy original"         to "gb",
        "dmg"                       to "gb",

        // Game Boy Color
        "gbc"                       to "gbc",    // ES-DE canonical
        "gameboy color"             to "gbc",
        "game boy color"            to "gbc",

        // GBA
        "gba"                       to "gba",    // ES-DE canonical
        "gameboy advance"           to "gba",
        "game boy advance"          to "gba",

        // NDS
        "nds"                       to "nds",    // ES-DE canonical
        "ds"                        to "nds",
        "nintendo ds"               to "nds",
        "desmume"                   to "nds",
        "drastic"                   to "nds",

        // 3DS — ES-DE uses "n3ds"
        "n3ds"                      to "n3ds",   // ES-DE canonical
        "3ds"                       to "n3ds",
        "nintendo 3ds"              to "n3ds",
        "citra"                     to "n3ds",

        // GameCube — ES-DE uses "gc"
        "gc"                        to "gc",     // ES-DE canonical
        "gamecube"                  to "gc",
        "nintendo gamecube"         to "gc",
        "dolphin"                   to "gc",     // Dolphin handles both GC and Wii

        // Wii
        "wii"                       to "wii",    // ES-DE canonical
        "nintendo wii"              to "wii",

        // Wii U
        "wiiu"                      to "wiiu",   // ES-DE canonical
        "wii u"                     to "wiiu",
        "nintendo wii u"            to "wiiu",
        "cemu"                      to "wiiu",

        // Switch
        "switch"                    to "switch", // ES-DE canonical
        "nintendo switch"           to "switch",
        "ryujinx"                   to "switch",
        "yuzu"                      to "switch",

        // Virtual Boy
        "virtualboy"                to "virtualboy", // ES-DE canonical
        "virtual boy"               to "virtualboy",

        // ── Sega ──────────────────────────────────────────────────────────

        // Mega Drive / Genesis
        "megadrive"                 to "megadrive", // ES-DE canonical
        "genesis"                   to "megadrive", // ES-DE alias
        "md"                        to "megadrive",
        "mega drive"                to "megadrive",
        "sega genesis"              to "megadrive",
        "sega mega drive"           to "megadrive",
        "sega megadrive"            to "megadrive",

        // Saturn
        "saturn"                    to "saturn",  // ES-DE canonical
        "sega saturn"               to "saturn",
        "yabasanshiro"              to "saturn",

        // Dreamcast
        "dreamcast"                 to "dreamcast", // ES-DE canonical
        "dc"                        to "dreamcast",
        "sega dreamcast"            to "dreamcast",
        "flycast"                   to "dreamcast",
        "redream"                   to "dreamcast",

        // Game Gear
        "gamegear"                  to "gamegear", // ES-DE canonical
        "game gear"                 to "gamegear",
        "gg"                        to "gamegear",
        "sega game gear"            to "gamegear",

        // Master System
        "mastersystem"              to "mastersystem", // ES-DE canonical
        "master system"             to "mastersystem",
        "sms"                       to "mastersystem",
        "sega master system"        to "mastersystem",
        "mark iii"                  to "mastersystem",

        // Sega CD
        "segacd"                    to "segacd",  // ES-DE canonical
        "sega cd"                   to "segacd",
        "mega cd"                   to "segacd",
        "megacd"                    to "segacd",

        // Sega 32X
        "sega32x"                   to "sega32x", // ES-DE canonical
        "32x"                       to "sega32x",
        "sega 32x"                  to "sega32x",

        // ── Atari ─────────────────────────────────────────────────────────

        "atari2600"                 to "atari2600", // ES-DE canonical
        "atari 2600"                to "atari2600",
        "atari"                     to "atari2600", // bare "atari" folder defaults to 2600

        "atari5200"                 to "atari5200", // ES-DE canonical
        "atari 5200"                to "atari5200",

        "atari7800"                 to "atari7800", // ES-DE canonical
        "atari 7800"                to "atari7800",

        "atarilynx"                 to "atarilynx", // ES-DE canonical
        "atari lynx"                to "atarilynx",
        "lynx"                      to "atarilynx",

        // ── NEC ───────────────────────────────────────────────────────────

        "pcengine"                  to "pcengine", // ES-DE canonical
        "turbografx"                to "pcengine", // common alias
        "turbografx-16"             to "pcengine",
        "turbografx16"              to "pcengine",
        "pc engine"                 to "pcengine",
        "pce"                       to "pcengine",
        "tg16"                      to "pcengine",
        "tgcd"                      to "pcengine",
        "pcenginecd"                to "pcengine",

        // ── SNK ───────────────────────────────────────────────────────────

        "neogeo"                    to "neogeo",  // ES-DE canonical
        "neo geo"                   to "neogeo",
        "neo-geo"                   to "neogeo",

        "ngp"                       to "ngp",     // ES-DE canonical (Neo Geo Pocket)
        "ngpc"                      to "ngp",
        "neo geo pocket"            to "ngp",
        "neogeopocket"              to "ngp",

        // ── Bandai ────────────────────────────────────────────────────────

        "wonderswan"                to "wonderswan",      // ES-DE canonical
        "wonderswancolor"           to "wonderswancolor", // ES-DE canonical
        "wonder swan"               to "wonderswan",
        "wonder swan color"         to "wonderswancolor",
        "wsc"                       to "wonderswancolor",

        // ── Commodore ─────────────────────────────────────────────────────

        "c64"                       to "c64",     // ES-DE canonical
        "commodore 64"              to "c64",
        "commodore64"               to "c64",
        "vice"                      to "c64",

        // ── Arcade ────────────────────────────────────────────────────────

        "mame"                      to "mame",    // ES-DE canonical
        "arcade"                    to "mame",    // ES-DE canonical alias
        "fbneo"                     to "mame",
        "finalburn"                 to "mame",
        "final burn"                to "mame",
        "fba"                       to "mame",
        "naomi"                     to "mame",

        // ── PC / Other ─────────────────────────────────────────────────────

        // ── Microsoft ──────────────────────────────────────────────────────
        "xbox360"                   to "x360",    // ES-DE canonical
        "x360"                      to "x360",
        "xbox 360"                  to "x360",

        // ── PC ─────────────────────────────────────────────────────────────
        "windows"                   to "windows", // ES-DE canonical
        "winlator"                  to "windows",
        "pc"                        to "windows",

        "dos"                       to "dos",
        "dosbox"                    to "dos",
        "ports"                     to "ports",
        "scummvm"                   to "scummvm",
    )

    // Walk path segments from deepest to shallowest and return the first match.
    // Returns null if no folder name maps to a known platform.
    fun detectFromPath(filePath: String): String? {
        val segments = filePath
            .replace('\\', '/')
            .split('/')
            .dropLast(1)   // drop the filename itself
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .reversed()    // deepest first

        for (segment in segments) {
            hints[segment]?.let { return it }
        }
        return null
    }

    // Direct lookup for a single folder name (e.g. a ROM-root subfolder). Drives the ES-DE
    // single-scan autoload: each root subfolder name is resolved to a platform id here.
    // Returns null when the name isn't a recognised ES-DE system folder.
    fun detectFromFolderName(name: String): String? =
        hints[name.trim().lowercase()]

    // The ES-DE canonical directory name to CREATE for a platform when setting up a fresh ROM
    // structure (the inverse of the hint map). Our platform ids already follow ES-DE names, so
    // this is identity except where ES-DE diverges. Using ES-DE names keeps libraries portable
    // to/from an actual ES-DE install.
    fun esDeFolderName(platformId: String): String = when (platformId) {
        "x360" -> "xbox360"   // ES-DE canonical Xbox 360 folder
        else   -> platformId
    }
}
