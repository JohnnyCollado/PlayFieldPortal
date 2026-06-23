package com.playfieldportal.feature.artwork.api

/**
 * Options that control which assets are downloaded and which source is preferred
 * for each asset type during a scrape run.
 */
data class ScrapeOptions(
    /** Use SteamGridDB as the first source for hero/banner art instead of ScreenScraper. */
    val preferSteamGridDbHeroes: Boolean = false,
    /** Use ScreenScraper as the first source for box art (default and recommended). */
    val preferScreenScraperBoxArt: Boolean = true,
    /** Download game manuals from ScreenScraper when available. */
    val downloadManuals: Boolean = false,
    /** Download video snaps from ScreenScraper when available. */
    val downloadVideoSnaps: Boolean = false,
    /** Download clear logos from any available source. */
    val downloadClearLogos: Boolean = true,
    /** Download hero/banner images. */
    val downloadHeroes: Boolean = true,
)
