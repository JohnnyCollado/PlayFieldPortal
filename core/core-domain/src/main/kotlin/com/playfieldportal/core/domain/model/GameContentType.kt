package com.playfieldportal.core.domain.model

/**
 * Classifies a [Game] row so aggregate views can filter by what the entry actually is.
 *
 * The "All Games" memory card shows only [GAME]; app-style entries (Android/Video/Music/etc.)
 * stay in their own categories and only ever appear in a user collection if explicitly added.
 */
enum class GameContentType {
    GAME,         // a real console / ROM game (or a manual game entry)
    ANDROID_APP,  // an installed Android app shortcut surfaced as a game-style entry
    VIDEO_APP,    // video / streaming app
    MUSIC_APP,    // music / audio app
    MEDIA,        // other media app
    SHORTCUT;     // generic launcher shortcut

    companion object {
        fun fromName(name: String?): GameContentType =
            entries.firstOrNull { it.name == name } ?: GAME
    }
}
