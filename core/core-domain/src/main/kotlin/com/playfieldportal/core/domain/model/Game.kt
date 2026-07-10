package com.playfieldportal.core.domain.model

/**
 * A single library entry. Despite the name this models both real games and Android app shortcuts —
 * [contentType] distinguishes them ([GameContentType.GAME] vs [GameContentType.ANDROID_APP]), and
 * only `GAME` rows aggregate into "All Games". A ROM-backed game carries [romPath]; an app shortcut
 * carries [packageName] instead. Artwork ([iconUri]/[heroUri]/[artworkUri]) is optional and applies
 * to both kinds.
 */
data class Game(
    val id: Long = 0,
    val title: String,
    val platformId: String,
    val romPath: String?          = null,   // raw path (null for native Android games); for SAF
                                            // games this is the derived path used by {rom_path}
    // SAF document content:// URI for the ROM. Present when the game came from a SAF library and is
    // the preferred launch handle (no storage permission needed); null for legacy raw-path games.
    val romUri: String?           = null,
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
    // Scraper database ids persisted after a successful match: re-scrapes fetch by id (never
    // re-matched), and a portable artwork library reconnects by id after a device migration.
    val ssId: Long?               = null,   // ScreenScraper
    val tgdbId: Long?             = null,   // TheGamesDB
    val igdbId: Long?             = null,   // IGDB
    // Streamed CRC-32 of the ROM payload (zip-inner for zipped cartridge ROMs), uppercase hex.
    val romCrc32: String?         = null,
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
