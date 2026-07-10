package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameContentType
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "games",
    indices = [
        Index("platform_id"),
        Index("is_favorite"),
        Index("last_played_at"),
        Index("rom_path", unique = true),
    ]
)
data class GameEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val title: String,

    @ColumnInfo(name = "platform_id")
    val platformId: String,

    @ColumnInfo(name = "rom_path")
    val romPath: String?,

    // SAF content:// URI for the ROM; null for legacy raw-path games. Not unique-indexed — dedupe
    // stays on rom_path (always populated, raw or SAF-derived).
    @ColumnInfo(name = "rom_uri")
    val romUri: String? = null,

    @ColumnInfo(name = "package_name")
    val packageName: String?,

    @ColumnInfo(name = "emulator_package")
    val emulatorPackage: String?,

    @ColumnInfo(name = "artwork_uri")
    val artworkUri: String?,

    @ColumnInfo(name = "hero_uri")
    val heroUri: String?,

    @ColumnInfo(name = "logo_uri")
    val logoUri: String?,

    @ColumnInfo(name = "icon_uri")
    val iconUri: String? = null,

    val description: String?,
    val developer: String?,
    val publisher: String?,

    @ColumnInfo(name = "release_year")
    val releaseYear: Int?,

    val genre: String?,

    @ColumnInfo(name = "steam_grid_db_id")
    val steamGridDbId: Long?,

    // Scraper database ids, persisted so re-scrapes can fetch by id (no re-matching) and so a
    // portable artwork library can reconnect by id after a device migration.
    @ColumnInfo(name = "ss_id")
    val ssId: Long? = null,

    @ColumnInfo(name = "tgdb_id")
    val tgdbId: Long? = null,

    @ColumnInfo(name = "igdb_id")
    val igdbId: Long? = null,

    // Streamed CRC-32 of the ROM payload (zip-inner for zipped cartridge ROMs), uppercase hex.
    // Computed opportunistically during ScreenScraper lookups; doubles as portable-identity
    // evidence. Null when the ROM is missing, too large to hash, or hasn't been scraped yet.
    @ColumnInfo(name = "rom_crc32")
    val romCrc32: String? = null,

    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,

    @ColumnInfo(name = "favorite_sort_order")
    val favoriteSortOrder: Int = 0,

    @ColumnInfo(name = "total_play_time_millis")
    val totalPlayTimeMillis: Long = 0,

    @ColumnInfo(name = "last_played_at")
    val lastPlayedAt: Long? = null,

    @ColumnInfo(name = "user_note")
    val userNote: String? = null,

    @ColumnInfo(name = "is_manual_entry")
    val isManualEntry: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    // Title resolved from a metadata scrape — updated by the scraper, never by ROM scanning.
    @ColumnInfo(name = "scraped_title")
    val scrapedTitle: String? = null,

    // User-set display name override — preserved across re-scrapes unless explicitly cleared.
    @ColumnInfo(name = "user_title_override")
    val userTitleOverride: String? = null,

    // Content classification (GAME / ANDROID_APP / VIDEO_APP / …). Only GAME rows aggregate
    // into "All Games". Stored as the enum name; defaults to GAME for legacy/console rows.
    @ColumnInfo(name = "content_type")
    val contentType: String = GameContentType.GAME.name,

    // Host app's launcher-shortcut id for harvested per-game entries (GameHub PCs, etc.).
    // Null for ordinary apps and ROM games. Launched via LauncherApps.startShortcut.
    @ColumnInfo(name = "launch_shortcut_id")
    val launchShortcutId: String? = null,

    // Captured legacy INSTALL_SHORTCUT launch intent (Intent.toUri), for BannerHub / old Winlator.
    @ColumnInfo(name = "launch_intent_uri")
    val launchIntentUri: String? = null,
)

fun GameEntity.toDomain() = Game(
    id                  = id,
    title               = title,
    platformId          = platformId,
    romPath             = romPath,
    romUri              = romUri,
    packageName         = packageName,
    emulatorPackage     = emulatorPackage,
    artworkUri          = artworkUri,
    heroUri             = heroUri,
    logoUri             = logoUri,
    iconUri             = iconUri,
    description         = description,
    developer           = developer,
    publisher           = publisher,
    releaseYear         = releaseYear,
    genre               = genre,
    steamGridDbId       = steamGridDbId,
    ssId                = ssId,
    tgdbId              = tgdbId,
    igdbId              = igdbId,
    romCrc32            = romCrc32,
    isFavorite          = isFavorite,
    favoriteSortOrder   = favoriteSortOrder,
    totalPlayTimeMillis = totalPlayTimeMillis,
    lastPlayedAt        = lastPlayedAt,
    userNote            = userNote,
    isManualEntry       = isManualEntry,
    scrapedTitle        = scrapedTitle,
    userTitleOverride   = userTitleOverride,
    contentType         = GameContentType.fromName(contentType),
    shortcutId          = launchShortcutId,
    launchIntentUri     = launchIntentUri,
)

fun Game.toEntity() = GameEntity(
    id                  = id,
    title               = title,
    platformId          = platformId,
    romPath             = romPath,
    romUri              = romUri,
    packageName         = packageName,
    emulatorPackage     = emulatorPackage,
    artworkUri          = artworkUri,
    heroUri             = heroUri,
    logoUri             = logoUri,
    iconUri             = iconUri,
    description         = description,
    developer           = developer,
    publisher           = publisher,
    releaseYear         = releaseYear,
    genre               = genre,
    steamGridDbId       = steamGridDbId,
    ssId                = ssId,
    tgdbId              = tgdbId,
    igdbId              = igdbId,
    romCrc32            = romCrc32,
    isFavorite          = isFavorite,
    favoriteSortOrder   = favoriteSortOrder,
    totalPlayTimeMillis = totalPlayTimeMillis,
    lastPlayedAt        = lastPlayedAt,
    userNote            = userNote,
    isManualEntry       = isManualEntry,
    scrapedTitle        = scrapedTitle,
    userTitleOverride   = userTitleOverride,
    contentType         = contentType.name,
    launchShortcutId    = shortcutId,
    launchIntentUri     = launchIntentUri,
)
