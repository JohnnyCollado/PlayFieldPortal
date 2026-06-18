package com.playfieldportal.launcher.debug

import com.playfieldportal.core.domain.model.Game

// Generates realistic fake game entries for debug scenarios.
// These are inserted into the real Room DB on debug launch
// so the full production code path is exercised.
object DebugGameFactory {

    fun ps2Games(): List<Game> = listOf(
        fakeGame(title = "Shadow of the Colossus",   platformId = "ps2", romPath = "/storage/emulated/0/ROMs/PS2/sotc.iso",   playTimeH = 12),
        fakeGame(title = "God of War",               platformId = "ps2", romPath = "/storage/emulated/0/ROMs/PS2/gow.iso",    playTimeH = 8),
        fakeGame(title = "Gran Turismo 4",           platformId = "ps2", romPath = "/storage/emulated/0/ROMs/PS2/gt4.iso",    playTimeH = 24),
        fakeGame(title = "Kingdom Hearts",           platformId = "ps2", romPath = "/storage/emulated/0/ROMs/PS2/kh.iso",     isFavorite = true),
        fakeGame(title = "Ico",                      platformId = "ps2", romPath = "/storage/emulated/0/ROMs/PS2/ico.iso"),
        fakeGame(title = "Silent Hill 2",            platformId = "ps2", romPath = "/storage/emulated/0/ROMs/PS2/sh2.iso"),
        fakeGame(title = "Metal Gear Solid 3",       platformId = "ps2", romPath = "/storage/emulated/0/ROMs/PS2/mgs3.iso",  playTimeH = 16, isFavorite = true),
        fakeGame(title = "Devil May Cry 3",          platformId = "ps2", romPath = "/storage/emulated/0/ROMs/PS2/dmc3.iso"),
        fakeGame(title = "Ratchet & Clank",          platformId = "ps2", romPath = "/storage/emulated/0/ROMs/PS2/rac.iso"),
        fakeGame(title = "Jak and Daxter",           platformId = "ps2", romPath = "/storage/emulated/0/ROMs/PS2/jak.iso"),
    )

    fun gbaGames(): List<Game> = listOf(
        fakeGame(title = "Pokémon FireRed",                    platformId = "gba", romPath = "/storage/emulated/0/ROMs/GBA/firered.gba",  playTimeH = 47, isFavorite = true),
        fakeGame(title = "The Legend of Zelda: Minish Cap",    platformId = "gba", romPath = "/storage/emulated/0/ROMs/GBA/minishcap.gba"),
        fakeGame(title = "Metroid Fusion",                     platformId = "gba", romPath = "/storage/emulated/0/ROMs/GBA/fusion.gba"),
        fakeGame(title = "Castlevania: Aria of Sorrow",        platformId = "gba", romPath = "/storage/emulated/0/ROMs/GBA/aria.gba"),
        fakeGame(title = "Fire Emblem",                        platformId = "gba", romPath = "/storage/emulated/0/ROMs/GBA/fe.gba",       playTimeH = 31),
        fakeGame(title = "Golden Sun",                         platformId = "gba", romPath = "/storage/emulated/0/ROMs/GBA/goldensun.gba"),
    )

    fun largeLibrary(): List<Game> {
        val platforms = listOf("ps2", "gba", "n64", "snes", "nes", "psp", "nds")
        return (1..100).map { i ->
            fakeGame(
                title      = "Debug Game $i",
                platformId = platforms[i % platforms.size],
                romPath    = "/storage/emulated/0/ROMs/debug_$i.rom",
                playTimeH  = if (i % 3 == 0) i else 0,
                isFavorite = i % 10 == 0,
            )
        }
    }

    fun missingRomGames(): List<Game> = listOf(
        // ROM path doesn't exist on disk — tests missing ROM detection
        fakeGame(title = "Missing Game 1", platformId = "ps2", romPath = "/storage/emulated/0/ROMs/PS2/nonexistent1.iso"),
        fakeGame(title = "Missing Game 2", platformId = "gba", romPath = "/storage/emulated/0/ROMs/GBA/nonexistent2.gba"),
    )

    fun gamesForScenario(scenario: DebugScenario): List<Game> = when (scenario) {
        DebugScenario.FULL_LIBRARY    -> ps2Games() + gbaGames()
        DebugScenario.FAVORITES_ONLY  -> (ps2Games() + gbaGames()).filter { it.isFavorite }
        DebugScenario.EMPTY_LIBRARY   -> emptyList()
        DebugScenario.SINGLE_GAME     -> listOf(ps2Games().first())
        DebugScenario.LARGE_LIBRARY   -> largeLibrary()
        DebugScenario.MISSING_ROMS    -> ps2Games() + missingRomGames()
        DebugScenario.NO_ARTWORK      -> (ps2Games() + gbaGames()).map { it.copy(artworkUri = null, heroUri = null) }
    }

    private fun fakeGame(
        title: String,
        platformId: String,
        romPath: String,
        playTimeH: Int = 0,
        isFavorite: Boolean = false,
    ) = Game(
        title               = title,
        platformId          = platformId,
        romPath             = romPath,
        isFavorite          = isFavorite,
        totalPlayTimeMillis = playTimeH * 3_600_000L,
        lastPlayedAt        = if (playTimeH > 0) System.currentTimeMillis() - (playTimeH * 3_600_000L) else null,
        isManualEntry       = true,
    )
}
