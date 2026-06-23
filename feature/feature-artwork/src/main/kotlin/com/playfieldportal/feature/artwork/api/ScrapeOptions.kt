package com.playfieldportal.feature.artwork.api

data class ScrapeOptions(
    val preferSteamGridDbHeroes: Boolean = false,
    val downloadClearLogos: Boolean = true,
    val downloadHeroes: Boolean = true,
)
