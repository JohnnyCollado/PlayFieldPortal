package com.playfieldportal.feature.artwork.api

data class ScrapeOptions(
    val preferSteamGridDbHeroes: Boolean = false,
    val downloadClearLogos: Boolean = true,
    val downloadHeroes: Boolean = true,
    // ScreenScraper-only extras. Manuals default ON (modest PDFs, capped); video snaps stay
    // OFF by default — they are the largest per-game asset.
    val downloadManuals: Boolean = true,
    val downloadVideoSnaps: Boolean = false,
    // Re-Scrape All sets this: skip the ss_media_cache read so upstream changes are picked
    // up (responses still refresh the cache).
    val bypassSsCache: Boolean = false,
    // Update Metadata sets this: text fields only — no artwork is downloaded, and no artwork
    // column is written (artwork-only sources aren't queried at all).
    val metadataOnly: Boolean = false,
)
