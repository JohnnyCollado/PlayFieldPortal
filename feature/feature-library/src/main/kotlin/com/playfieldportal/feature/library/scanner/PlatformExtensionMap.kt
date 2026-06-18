package com.playfieldportal.feature.library.scanner

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlatformExtensionMap @Inject constructor() {

    // Unambiguous single-file formats — one extension = one platform
    private val definitiveExtensions = mapOf(
        // PlayStation
        "iso"   to "ps2",
        "cso"   to "psp",
        "pbp"   to "psp",

        // Nintendo
        "nes"   to "nes",
        "fds"   to "nes",
        "smc"   to "snes",
        "sfc"   to "snes",
        "gba"   to "gba",
        "gbc"   to "gbc",
        "gb"    to "gbc",
        "n64"   to "n64",
        "z64"   to "n64",
        "v64"   to "n64",
        "nds"   to "nds",
        "3ds"   to "3ds",
        "cia"   to "3ds",
        "gcm"   to "gamecube",
        "gcz"   to "gamecube",
        "rvz"   to "gamecube",
        "wbfs"  to "wii",
        "wad"   to "wii",
        "nsp"   to "switch",
        "xci"   to "switch",

        // Sega — unambiguous
        "md"    to "megadrive",
        "gen"   to "megadrive",
        "smd"   to "megadrive",

        // Arcade
        "zip"   to "mame",
        "7z"    to "mame",

        // Other
        "pce"   to "turbografx",
        "lnx"   to "atari_lynx",
        "a78"   to "atari7800",
    )

    // Extensions that need context from sibling files to resolve platform
    // These are handled by DiscImageResolver before the main scan loop
    val contextDependentExtensions = setOf("bin", "cue", "img", "chd")

    fun detectPlatform(extension: String): String? =
        definitiveExtensions[extension.lowercase()]

    fun isDefinitive(extension: String): Boolean =
        extension.lowercase() in definitiveExtensions

    fun isContextDependent(extension: String): Boolean =
        extension.lowercase() in contextDependentExtensions

    fun isKnownExtension(extension: String): Boolean =
        isDefinitive(extension) || isContextDependent(extension)
}
