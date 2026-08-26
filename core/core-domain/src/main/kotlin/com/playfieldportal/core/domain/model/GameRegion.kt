package com.playfieldportal.core.domain.model

/**
 * TV format / disc region detected from the ROM image content (never the filename) — the license
 * string on a PS1 disc, `REGION=` in a PS2 SYSTEM.CNF, the region byte in a GameCube/Wii boot.bin,
 * the IP.BIN region char on Saturn/Dreamcast, a UMD serial on PSP, a TITLE_ID on PS3, or the XEX
 * game-region bitfield on Xbox 360. Null on a [Game] means "not detected" (unreadable file, a
 * compressed container like .chd, or a platform with no embedded region marker).
 */
enum class GameRegion {
    NTSC_U,
    PAL,
    NTSC_J,
    ;

    companion object {
        /** Null-safe parse of a stored enum name; null for null/unknown values. */
        fun fromName(name: String?): GameRegion? = name?.let { entries.firstOrNull { e -> e.name == it } }
    }
}
