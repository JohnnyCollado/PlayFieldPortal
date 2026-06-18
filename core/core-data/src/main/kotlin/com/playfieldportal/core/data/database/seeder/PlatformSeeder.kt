package com.playfieldportal.core.data.database.seeder

import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.entity.PlatformEntity
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// Seeds built-in platform definitions on first launch.
// Uses INSERT OR IGNORE so re-runs are safe and never overwrite user customizations.
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
                id           = "ps1",
                name         = "PlayStation",
                shortName    = "PS1",
                iconRes      = "ic_platform_ps1",
                accentColor  = 0xFF003087L,  // PlayStation navy blue
                romExtensions = "cue,bin,iso,pbp",
            ),
            PlatformEntity(
                id           = "ps2",
                name         = "PlayStation 2",
                shortName    = "PS2",
                iconRes      = "ic_platform_ps2",
                accentColor  = 0xFF00439CL,  // PS2 blue
                romExtensions = "iso,bin",
            ),
            PlatformEntity(
                id           = "psp",
                name         = "PlayStation Portable",
                shortName    = "PSP",
                iconRes      = "ic_platform_psp",
                accentColor  = 0xFF003791L,
                romExtensions = "iso,cso,pbp",
            ),

            // ── Nintendo ───────────────────────────────────────────────────
            PlatformEntity(
                id           = "nes",
                name         = "Nintendo Entertainment System",
                shortName    = "NES",
                iconRes      = "ic_platform_nes",
                accentColor  = 0xFFE60012L,  // Nintendo red
                romExtensions = "nes,fds",
            ),
            PlatformEntity(
                id           = "snes",
                name         = "Super Nintendo",
                shortName    = "SNES",
                iconRes      = "ic_platform_snes",
                accentColor  = 0xFF8B1A8BL,  // SNES purple
                romExtensions = "smc,sfc",
            ),
            PlatformEntity(
                id           = "n64",
                name         = "Nintendo 64",
                shortName    = "N64",
                iconRes      = "ic_platform_n64",
                accentColor  = 0xFF009AC7L,  // N64 blue
                romExtensions = "n64,z64,v64",
            ),
            PlatformEntity(
                id           = "gbc",
                name         = "Game Boy Color",
                shortName    = "GBC",
                iconRes      = "ic_platform_gbc",
                accentColor  = 0xFF8BBB11L,  // GBC green
                romExtensions = "gb,gbc",
            ),
            PlatformEntity(
                id           = "gba",
                name         = "Game Boy Advance",
                shortName    = "GBA",
                iconRes      = "ic_platform_gba",
                accentColor  = 0xFF6A0DADL,  // GBA purple
                romExtensions = "gba",
            ),
            PlatformEntity(
                id           = "nds",
                name         = "Nintendo DS",
                shortName    = "NDS",
                iconRes      = "ic_platform_nds",
                accentColor  = 0xFFCC0000L,
                romExtensions = "nds",
            ),
            PlatformEntity(
                id           = "3ds",
                name         = "Nintendo 3DS",
                shortName    = "3DS",
                iconRes      = "ic_platform_3ds",
                accentColor  = 0xFFCC0000L,
                romExtensions = "3ds,cia",
            ),
            PlatformEntity(
                id           = "gamecube",
                name         = "GameCube",
                shortName    = "GC",
                iconRes      = "ic_platform_gamecube",
                accentColor  = 0xFF6A0DADL,  // GC indigo
                romExtensions = "iso,gcm,gcz,rvz",
            ),
            PlatformEntity(
                id           = "wii",
                name         = "Wii",
                shortName    = "Wii",
                iconRes      = "ic_platform_wii",
                accentColor  = 0xFFC0C0C0L,  // Wii white/silver
                romExtensions = "iso,wbfs,wad",
            ),
            PlatformEntity(
                id           = "switch",
                name         = "Nintendo Switch",
                shortName    = "Switch",
                iconRes      = "ic_platform_switch",
                accentColor  = 0xFFE4000FL,  // Switch red
                romExtensions = "nsp,xci",
            ),

            // ── Sega ───────────────────────────────────────────────────────
            PlatformEntity(
                id           = "megadrive",
                name         = "Sega Mega Drive / Genesis",
                shortName    = "MD",
                iconRes      = "ic_platform_megadrive",
                accentColor  = 0xFF1A1A8CL,  // Sega blue
                romExtensions = "md,gen,smd,bin",
            ),
            PlatformEntity(
                id           = "saturn",
                name         = "Sega Saturn",
                shortName    = "Saturn",
                iconRes      = "ic_platform_saturn",
                accentColor  = 0xFF404040L,
                romExtensions = "cue,chd",
            ),

            // ── Arcade ─────────────────────────────────────────────────────
            PlatformEntity(
                id           = "mame",
                name         = "Arcade (MAME)",
                shortName    = "MAME",
                iconRes      = "ic_platform_arcade",
                accentColor  = 0xFFFF6600L,  // arcade orange
                romExtensions = "zip,7z",
            ),

            // ── Windows / Android ──────────────────────────────────────────
            PlatformEntity(
                id           = "windows",
                name         = "Windows (Winlator)",
                shortName    = "PC",
                iconRes      = "ic_platform_windows",
                accentColor  = 0xFF0078D4L,  // Windows blue
                romExtensions = "",
            ),
            PlatformEntity(
                id           = "android",
                name         = "Android",
                shortName    = "Android",
                iconRes      = "ic_platform_android",
                accentColor  = 0xFF3DDC84L,  // Android green
                romExtensions = "",
            ),
        )
    }
}
