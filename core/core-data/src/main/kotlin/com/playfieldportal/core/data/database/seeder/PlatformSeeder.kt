package com.playfieldportal.core.data.database.seeder

import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.entity.PlatformEntity
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// Seeds built-in platform definitions on first launch.
// Uses INSERT OR IGNORE so re-runs are safe and never overwrite user customizations.
// Platform IDs follow EmulationStation DE canonical folder names where practical.
@Singleton
class PlatformSeeder @Inject constructor(
    private val platformDao: PlatformDao,
) {
    suspend fun seed() {
        platformDao.insertAll(DEFAULT_PLATFORMS)
        Timber.i("Platform seed complete — ${DEFAULT_PLATFORMS.size} platforms available")
    }

    companion object {
        private val DEFAULT_PLATFORMS = listOf(

            // ── Sony ───────────────────────────────────────────────────────
            PlatformEntity(
                id            = "psx",
                name          = "PlayStation",
                shortName     = "PS1",
                iconRes       = "ic_platform_ps1",
                accentColor   = 0xFF003087L,
                romExtensions = "cue,bin,iso,pbp,chd,ecm,mds,m3u",
            ),
            PlatformEntity(
                id            = "ps2",
                name          = "PlayStation 2",
                shortName     = "PS2",
                iconRes       = "ic_platform_ps2",
                accentColor   = 0xFF00439CL,
                romExtensions = "iso,bin,chd",
            ),
            PlatformEntity(
                id            = "psp",
                name          = "PlayStation Portable",
                shortName     = "PSP",
                iconRes       = "ic_platform_psp",
                accentColor   = 0xFF003791L,
                romExtensions = "iso,cso,pbp",
            ),
            PlatformEntity(
                id            = "ps3",
                name          = "PlayStation 3",
                shortName     = "PS3",
                iconRes       = "ic_platform_ps3",
                accentColor   = 0xFF003087L,
                romExtensions = "",
            ),
            PlatformEntity(
                id            = "psvita",
                name          = "PlayStation Vita",
                shortName     = "Vita",
                iconRes       = "ic_platform_psvita",
                accentColor   = 0xFF003087L,
                romExtensions = "vpk",
            ),

            // ── Nintendo ───────────────────────────────────────────────────
            PlatformEntity(
                id            = "nes",
                name          = "Nintendo Entertainment System",
                shortName     = "NES",
                iconRes       = "ic_platform_nes",
                accentColor   = 0xFFE60012L,
                romExtensions = "nes,fds,unf,unif",
            ),
            PlatformEntity(
                id            = "snes",
                name          = "Super Nintendo",
                shortName     = "SNES",
                iconRes       = "ic_platform_snes",
                accentColor   = 0xFF8B1A8BL,
                romExtensions = "smc,sfc,swc",
            ),
            PlatformEntity(
                id            = "n64",
                name          = "Nintendo 64",
                shortName     = "N64",
                iconRes       = "ic_platform_n64",
                accentColor   = 0xFF009AC7L,
                romExtensions = "n64,z64,v64",
            ),
            PlatformEntity(
                id            = "gb",
                name          = "Game Boy",
                shortName     = "GB",
                iconRes       = "ic_platform_gb",
                accentColor   = 0xFF8BBB11L,
                romExtensions = "gb,dmg",
            ),
            PlatformEntity(
                id            = "gbc",
                name          = "Game Boy Color",
                shortName     = "GBC",
                iconRes       = "ic_platform_gbc",
                accentColor   = 0xFF8BBB11L,
                romExtensions = "gbc",
            ),
            PlatformEntity(
                id            = "gba",
                name          = "Game Boy Advance",
                shortName     = "GBA",
                iconRes       = "ic_platform_gba",
                accentColor   = 0xFF6A0DADL,
                romExtensions = "gba,agb",
            ),
            PlatformEntity(
                id            = "nds",
                name          = "Nintendo DS",
                shortName     = "NDS",
                iconRes       = "ic_platform_nds",
                accentColor   = 0xFFCC0000L,
                romExtensions = "nds,dsi",
            ),
            PlatformEntity(
                id            = "n3ds",
                name          = "Nintendo 3DS",
                shortName     = "3DS",
                iconRes       = "ic_platform_3ds",
                accentColor   = 0xFFCC0000L,
                romExtensions = "3ds,cia",
            ),
            PlatformEntity(
                id            = "gc",
                name          = "GameCube",
                shortName     = "GC",
                iconRes       = "ic_platform_gamecube",
                accentColor   = 0xFF6A0DADL,
                romExtensions = "iso,gcm,gcz,rvz,wbfs,ciso",
            ),
            PlatformEntity(
                id            = "wii",
                name          = "Wii",
                shortName     = "Wii",
                iconRes       = "ic_platform_wii",
                accentColor   = 0xFFC0C0C0L,
                romExtensions = "iso,wbfs,wad,rvz,gcz,ciso",
            ),
            PlatformEntity(
                id            = "wiiu",
                name          = "Wii U",
                shortName     = "WiiU",
                iconRes       = "ic_platform_wiiu",
                accentColor   = 0xFF009AC7L,
                romExtensions = "rpx,wua,wud",
            ),
            PlatformEntity(
                id            = "switch",
                name          = "Nintendo Switch",
                shortName     = "Switch",
                iconRes       = "ic_platform_switch",
                accentColor   = 0xFFE4000FL,
                romExtensions = "nsp,xci,nro,nso",
            ),
            PlatformEntity(
                id            = "virtualboy",
                name          = "Virtual Boy",
                shortName     = "VB",
                iconRes       = "ic_platform_virtualboy",
                accentColor   = 0xFFCC0000L,
                romExtensions = "vb,vboy",
            ),

            // ── Sega ───────────────────────────────────────────────────────
            PlatformEntity(
                id            = "megadrive",
                name          = "Sega Mega Drive / Genesis",
                shortName     = "MD",
                iconRes       = "ic_platform_megadrive",
                accentColor   = 0xFF1A1A8CL,
                romExtensions = "md,gen,smd,bin,68k",
            ),
            PlatformEntity(
                id            = "mastersystem",
                name          = "Sega Master System",
                shortName     = "SMS",
                iconRes       = "ic_platform_mastersystem",
                accentColor   = 0xFF1A1A8CL,
                romExtensions = "sms,sg",
            ),
            PlatformEntity(
                id            = "gamegear",
                name          = "Sega Game Gear",
                shortName     = "GG",
                iconRes       = "ic_platform_gamegear",
                accentColor   = 0xFF1A1A8CL,
                romExtensions = "gg",
            ),
            PlatformEntity(
                id            = "saturn",
                name          = "Sega Saturn",
                shortName     = "Saturn",
                iconRes       = "ic_platform_saturn",
                accentColor   = 0xFF404040L,
                romExtensions = "cue,chd,iso,mds,m3u",
            ),
            PlatformEntity(
                id            = "dreamcast",
                name          = "Sega Dreamcast",
                shortName     = "DC",
                iconRes       = "ic_platform_dreamcast",
                accentColor   = 0xFFE87722L,
                romExtensions = "gdi,cdi,chd,iso,m3u",
            ),
            PlatformEntity(
                id            = "segacd",
                name          = "Sega CD / Mega CD",
                shortName     = "SCD",
                iconRes       = "ic_platform_segacd",
                accentColor   = 0xFF1A1A8CL,
                romExtensions = "cue,chd,iso,m3u",
            ),
            PlatformEntity(
                id            = "sega32x",
                name          = "Sega 32X",
                shortName     = "32X",
                iconRes       = "ic_platform_sega32x",
                accentColor   = 0xFF1A1A8CL,
                romExtensions = "32x,bin,md",
            ),

            // ── Atari ──────────────────────────────────────────────────────
            PlatformEntity(
                id            = "atari2600",
                name          = "Atari 2600",
                shortName     = "2600",
                iconRes       = "ic_platform_atari2600",
                accentColor   = 0xFFCC4400L,
                romExtensions = "a26,bin",
            ),
            PlatformEntity(
                id            = "atari5200",
                name          = "Atari 5200",
                shortName     = "5200",
                iconRes       = "ic_platform_atari5200",
                accentColor   = 0xFFCC4400L,
                romExtensions = "a52,bin,car",
            ),
            PlatformEntity(
                id            = "atari7800",
                name          = "Atari 7800",
                shortName     = "7800",
                iconRes       = "ic_platform_atari7800",
                accentColor   = 0xFFCC4400L,
                romExtensions = "a78,bin",
            ),
            PlatformEntity(
                id            = "atarilynx",
                name          = "Atari Lynx",
                shortName     = "Lynx",
                iconRes       = "ic_platform_atarilynx",
                accentColor   = 0xFFCC4400L,
                romExtensions = "lnx,lyx",
            ),

            // ── NEC ────────────────────────────────────────────────────────
            PlatformEntity(
                id            = "pcengine",
                name          = "PC Engine / TurboGrafx-16",
                shortName     = "PCE",
                iconRes       = "ic_platform_pcengine",
                accentColor   = 0xFFFFAA00L,
                romExtensions = "pce,sgx,cue,chd,m3u",
            ),

            // ── SNK ────────────────────────────────────────────────────────
            PlatformEntity(
                id            = "neogeo",
                name          = "Neo Geo",
                shortName     = "NG",
                iconRes       = "ic_platform_neogeo",
                accentColor   = 0xFFFFD700L,
                romExtensions = "neo,zip,7z",
            ),
            PlatformEntity(
                id            = "ngp",
                name          = "Neo Geo Pocket",
                shortName     = "NGP",
                iconRes       = "ic_platform_ngp",
                accentColor   = 0xFFFFD700L,
                romExtensions = "ngp,ngc,npc",
            ),

            // ── Bandai ─────────────────────────────────────────────────────
            PlatformEntity(
                id            = "wonderswan",
                name          = "WonderSwan",
                shortName     = "WS",
                iconRes       = "ic_platform_wonderswan",
                accentColor   = 0xFF888888L,
                romExtensions = "ws,bin",
            ),
            PlatformEntity(
                id            = "wonderswancolor",
                name          = "WonderSwan Color",
                shortName     = "WSC",
                iconRes       = "ic_platform_wonderswancolor",
                accentColor   = 0xFF888888L,
                romExtensions = "wsc,bin",
            ),

            // ── Commodore ──────────────────────────────────────────────────
            PlatformEntity(
                id            = "c64",
                name          = "Commodore 64",
                shortName     = "C64",
                iconRes       = "ic_platform_c64",
                accentColor   = 0xFF8B4513L,
                romExtensions = "d64,g64,t64,tap,prg,crt,d81",
            ),

            // ── Arcade ─────────────────────────────────────────────────────
            PlatformEntity(
                id            = "mame",
                name          = "Arcade (MAME)",
                shortName     = "MAME",
                iconRes       = "ic_platform_arcade",
                accentColor   = 0xFFFF6600L,
                romExtensions = "zip,7z,neo",
            ),

            // ── Microsoft ──────────────────────────────────────────────────
            PlatformEntity(
                id            = "x360",
                name          = "Xbox 360",
                shortName     = "X360",
                iconRes       = "ic_platform_xbox360",
                accentColor   = 0xFF107C10L,
                romExtensions = "iso,xex,zar,xbla",
                preferredEmulatorPackage = "emu.x360.mobile",
            ),

            // ── Windows / Android ──────────────────────────────────────────
            PlatformEntity(
                id            = "windows",
                name          = "Windows Games",
                shortName     = "PC",
                iconRes       = "ic_platform_windows",
                accentColor   = 0xFF0078D4L,
                romExtensions = "",
            ),
            PlatformEntity(
                id            = "android",
                name          = "Android",
                shortName     = "Android",
                iconRes       = "ic_platform_android",
                accentColor   = 0xFF3DDC84L,
                romExtensions = "",
            ),
        )
    }
}
