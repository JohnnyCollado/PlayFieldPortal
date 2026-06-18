package com.playfieldportal.core.domain.model

data class Game(
    val id: Long = 0,
    val title: String,
    val platformId: String,
    val romPath: String?          = null,   // null for native Android games
    val packageName: String?      = null,   // null for ROM-based games
    val emulatorPackage: String?  = null,   // preferred emulator override
    val artworkUri: String?       = null,   // cached SteamGridDB art path
    val heroUri: String?          = null,   // hero/banner artwork
    val logoUri: String?          = null,   // logo artwork
    val description: String?      = null,
    val developer: String?        = null,
    val publisher: String?        = null,
    val releaseYear: Int?         = null,
    val genre: String?            = null,
    val steamGridDbId: Long?      = null,
    val isFavorite: Boolean = false,
    val favoriteSortOrder: Int = 0,
    val totalPlayTimeMillis: Long = 0,
    val lastPlayedAt: Long? = null,
    val userNote: String?   = null,
    val isManualEntry: Boolean = false,
)
