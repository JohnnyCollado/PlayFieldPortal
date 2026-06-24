package com.playfieldportal.core.domain.model

data class Game(
    val id: Long = 0,
    val title: String,
    val platformId: String,
    val romPath: String?          = null,   // null for native Android games
    val packageName: String?      = null,   // null for ROM-based games
    val emulatorPackage: String?  = null,   // preferred emulator override
    val artworkUri: String?       = null,   // cached box/grid art path
    val heroUri: String?          = null,   // hero/banner artwork (full-screen background)
    val logoUri: String?          = null,   // logo artwork
    val iconUri: String?          = null,   // landscape 144:80 icon art (SteamGridDB horizontal grid)
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
    // Title derived from a metadata scrape (e.g. "Super Mario World" from "Super_Mario_World_USA.sfc").
    // Never overwrites userTitleOverride.
    val scrapedTitle: String?      = null,
    // User-set display name override. Takes priority over everything else in the UI.
    val userTitleOverride: String? = null,
    // What this entry actually is. Drives "All Games" filtering — only GAME aggregates there.
    val contentType: GameContentType = GameContentType.GAME,
    // For launcher-shortcut entries harvested from another app (GameHub PCs, etc.): the host
    // app's published shortcut id. Launched via LauncherApps.startShortcut(packageName, this).
    // Null for ordinary apps (launched by package) and ROM games.
    val shortcutId: String? = null,
    // For legacy INSTALL_SHORTCUT entries (BannerHub, old Winlator): the captured launch Intent
    // serialized via Intent.toUri(URI_INTENT_SCHEME). Launched by parsing and starting it.
    val launchIntentUri: String? = null,
) {
    // Resolved display name: user override → scraped metadata title → raw scan title.
    val displayTitle: String get() = userTitleOverride ?: scrapedTitle ?: title
}
