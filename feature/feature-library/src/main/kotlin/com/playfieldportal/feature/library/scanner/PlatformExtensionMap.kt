package com.playfieldportal.feature.library.scanner

import javax.inject.Inject
import javax.inject.Singleton

// Extension-to-platform mapping aligned with EmulationStation DE conventions.
// Platform IDs match PlatformSeeder IDs (ES-DE canonical folder names where practical).
@Singleton
class PlatformExtensionMap @Inject constructor() {

    // Unambiguous: one extension = exactly one platform.
    // Compressed archives (.zip, .7z) are excluded here — they're multi-platform
    // and resolved via folder hints only.
    private val definitiveExtensions = mapOf(

        // ── PlayStation Portable ───────────────────────────────────────────
        "cso"   to "psp",
        "pbp"   to "psp",       // PSP homebrew/PSN; also used for PS1-on-PSP but psp is safer default

        // ── Nintendo ──────────────────────────────────────────────────────
        "nes"   to "nes",
        "fds"   to "nes",
        "unf"   to "nes",
        "unif"  to "nes",
        "smc"   to "snes",
        "sfc"   to "snes",
        "swc"   to "snes",
        "n64"   to "n64",
        "z64"   to "n64",
        "v64"   to "n64",
        "gb"    to "gb",
        "dmg"   to "gb",
        "gbc"   to "gbc",
        "gba"   to "gba",
        "agb"   to "gba",
        "nds"   to "nds",
        "dsi"   to "nds",
        "3ds"   to "n3ds",
        "cia"   to "n3ds",
        "gcm"   to "gc",
        "gcz"   to "gc",
        "rvz"   to "gc",        // Dolphin compressed
        "wbfs"  to "wii",
        "wad"   to "wii",
        "rpx"   to "wiiu",
        "wua"   to "wiiu",
        "wud"   to "wiiu",
        "nsp"   to "switch",
        "xci"   to "switch",
        "nro"   to "switch",
        "nso"   to "switch",
        "vb"    to "virtualboy",
        "vboy"  to "virtualboy",

        // ── Sega — unambiguous ────────────────────────────────────────────
        "md"    to "megadrive",
        "gen"   to "megadrive",
        "smd"   to "megadrive",
        "68k"   to "megadrive",
        "32x"   to "sega32x",
        "sms"   to "mastersystem",
        "sg"    to "mastersystem",
        "gg"    to "gamegear",
        "gdi"   to "dreamcast",  // GDI dump — only Dreamcast
        "cdi"   to "dreamcast",

        // ── Atari ─────────────────────────────────────────────────────────
        "a26"   to "atari2600",
        "a52"   to "atari5200",
        "car"   to "atari5200",
        "a78"   to "atari7800",
        "lnx"   to "atarilynx",
        "lyx"   to "atarilynx",

        // ── NEC ───────────────────────────────────────────────────────────
        "pce"   to "pcengine",
        "sgx"   to "pcengine",

        // ── SNK ───────────────────────────────────────────────────────────
        "neo"   to "neogeo",    // Neo Geo arcade ROM
        "ngp"   to "ngp",       // Neo Geo Pocket
        "ngc"   to "ngp",       // Neo Geo Pocket Color
        "npc"   to "ngp",

        // ── Bandai ────────────────────────────────────────────────────────
        "ws"    to "wonderswan",
        "wsc"   to "wonderswancolor",

        // ── Sony Vita ─────────────────────────────────────────────────────
        "vpk"   to "psvita",

        // ── Commodore ─────────────────────────────────────────────────────
        "d64"   to "c64",
        "g64"   to "c64",
        "t64"   to "c64",
        "tap"   to "c64",
        "prg"   to "c64",
        "crt"   to "c64",
        "d81"   to "c64",
    )

    // Extensions that are ambiguous across platforms — platform is determined by:
    //   1. Folder name hint  (PlatformFolderHintResolver)
    //   2. Disc resolver     (DiscImageResolver — for .cue/.bin groups)
    //   3. Default fallback  (listed in folderSensitiveDefaults below)
    val contextDependentExtensions = setOf("bin", "cue", "img", "chd", "mds", "m3u", "ccd")

    // These extensions are common enough to scan but need a folder hint to pick the right platform.
    // Default is used when no folder hint is found.
    val folderSensitiveExtensions = mapOf(
        "iso" to "ps2",   // default PS2; overridden by folder hints (psp, psx, gc, wii, saturn, etc.)
        "zip" to "mame",  // default MAME/arcade; overridden by folder hints (nes, snes, gba, etc.)
        "7z"  to "mame",  // same — compressed archives are used by almost every platform
    )

    fun detectPlatform(extension: String): String? =
        definitiveExtensions[extension.lowercase()]

    fun folderSensitiveDefault(extension: String): String? =
        folderSensitiveExtensions[extension.lowercase()]

    fun isFolderSensitive(extension: String): Boolean =
        extension.lowercase() in folderSensitiveExtensions

    fun isDefinitive(extension: String): Boolean =
        extension.lowercase() in definitiveExtensions

    fun isContextDependent(extension: String): Boolean =
        extension.lowercase() in contextDependentExtensions

    fun isKnownExtension(extension: String): Boolean =
        isDefinitive(extension) || isContextDependent(extension) || isFolderSensitive(extension)
}
