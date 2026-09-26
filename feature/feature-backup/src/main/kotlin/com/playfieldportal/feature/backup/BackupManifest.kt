package com.playfieldportal.feature.backup

import kotlinx.serialization.Serializable

// v2 — adds the remaining user-owned tables (memory cards, platform prefs, app overrides,
// collections, themes, hidden placements, music/video/photo libraries + playlists) and bundles
// the internal-storage assets (game artwork, custom wallpaper, custom emulator profiles) under
// the "files/" prefix so a restore into a different package/data-dir is complete and portable.
// v1 backups (games/categories/settings only) still restore — missing entries are simply skipped.
const val BACKUP_FORMAT_VERSION = 2
const val BACKUP_FILE_EXTENSION = ".pfpbackup"

// Bundled internal-storage files are stored under this prefix, preserving their path relative to
// the app's filesDir (e.g. "files/artwork/12/hero.jpg"). Everything else in the ZIP is JSON.
const val BACKUP_FILES_PREFIX = "files/"

// Entry names inside the ZIP archive
object BackupEntry {
    const val MANIFEST       = "manifest.json"
    const val GAMES          = "games.json"
    const val CATEGORIES     = "categories.json"
    const val CATEGORY_ITEMS = "category_items.json"
    const val PLAY_SESSIONS  = "play_sessions.json"
    const val SETTINGS       = "settings.json"

    // v2 tables
    const val PLATFORMS            = "platforms.json"
    const val MEMORY_CARDS         = "memory_cards.json"
    const val APP_OVERRIDES        = "app_overrides.json"
    const val COLLECTIONS          = "collections.json"
    const val COLLECTION_GAMES     = "collection_games.json"
    const val THEMES               = "themes.json"
    const val HIDDEN_PLACEMENTS    = "hidden_placements.json"
    const val MUSIC_FOLDERS        = "music_folders.json"
    const val MUSIC_TRACKS         = "music_tracks.json"
    const val PLAYLISTS            = "playlists.json"
    const val PLAYLIST_TRACKS      = "playlist_tracks.json"
    const val VIDEO_LIBRARIES      = "video_libraries.json"
    const val VIDEOS               = "videos.json"
    const val VIDEO_PLAYLISTS      = "video_playlists.json"
    const val VIDEO_PLAYLIST_ITEMS = "video_playlist_items.json"
    const val PHOTO_LIBRARIES      = "photo_libraries.json"
    const val PHOTOS               = "photos.json"

    // Achievements (selective sync). Optional: older archives simply lack them.
    const val ACHIEVEMENT_IDENTITIES = "achievement_identities.json"
    const val ACHIEVEMENT_SYNC_STATE = "achievement_sync_state.json"
    const val ACHIEVEMENT_SETS       = "achievement_sets.json"
    const val ACHIEVEMENT_COINS      = "achievement_coins.json"
    const val PROVIDER_GAME_LINKS    = "provider_game_links.json"

    /**
     * The picked Local Steam game folders. Its own entry, and restored under its own presence
     * check, because the registry is not game-scoped: folding it into the achievement gate would
     * let an archive that predates the registry wipe the folders on the device it restores onto.
     */
    const val LOCAL_STEAM_FOLDERS    = "local_steam_folders.json"
}

@Serializable
data class BackupManifest(
    val formatVersion: Int    = BACKUP_FORMAT_VERSION,
    val appVersionCode: Int,
    val appVersionName: String,
    val createdAt: Long,              // epoch ms
    val gameCount: Int,
    val sessionCount: Int,
    val categoryCount: Int,
)

// Flattened settings snapshot stored as key → value string pairs
@Serializable
data class SettingsSnapshot(
    val entries: Map<String, String> = emptyMap(),
)
