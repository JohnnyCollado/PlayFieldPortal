package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.playfieldportal.core.domain.model.Game
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
)

fun GameEntity.toDomain() = Game(
    id                  = id,
    title               = title,
    platformId          = platformId,
    romPath             = romPath,
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
    isFavorite          = isFavorite,
    favoriteSortOrder   = favoriteSortOrder,
    totalPlayTimeMillis = totalPlayTimeMillis,
    lastPlayedAt        = lastPlayedAt,
    userNote            = userNote,
    isManualEntry       = isManualEntry,
    scrapedTitle        = scrapedTitle,
    userTitleOverride   = userTitleOverride,
)

fun Game.toEntity() = GameEntity(
    id                  = id,
    title               = title,
    platformId          = platformId,
    romPath             = romPath,
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
    isFavorite          = isFavorite,
    favoriteSortOrder   = favoriteSortOrder,
    totalPlayTimeMillis = totalPlayTimeMillis,
    lastPlayedAt        = lastPlayedAt,
    userNote            = userNote,
    isManualEntry       = isManualEntry,
    scrapedTitle        = scrapedTitle,
    userTitleOverride   = userTitleOverride,
)
