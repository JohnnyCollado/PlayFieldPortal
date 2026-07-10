package com.playfieldportal.feature.artwork.api

data class ScrapeOptions(
    val preferSteamGridDbHeroes: Boolean = false,
    val downloadClearLogos: Boolean = true,
    val downloadHeroes: Boolean = true,
    // ScreenScraper-only extras. Default OFF — manuals and video snaps are large files.
    val downloadManuals: Boolean = false,
    val downloadVideoSnaps: Boolean = false,
)
