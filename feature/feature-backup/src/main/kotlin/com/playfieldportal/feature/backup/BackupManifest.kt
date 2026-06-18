package com.playfieldportal.feature.backup

import kotlinx.serialization.Serializable

const val BACKUP_FORMAT_VERSION = 1
const val BACKUP_FILE_EXTENSION = ".pfpbackup"
const val BACKUP_FOLDER = "PlayFieldPortal/backups"

// Entry names inside the ZIP archive
object BackupEntry {
    const val MANIFEST       = "manifest.json"
    const val GAMES          = "games.json"
    const val CATEGORIES     = "categories.json"
    const val CATEGORY_ITEMS = "category_items.json"
    const val PLAY_SESSIONS  = "play_sessions.json"
    const val SETTINGS       = "settings.json"
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
