package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Provider achievement metadata cached apart from the player's unlocks — for Steam, the schema
 * (names, descriptions, icons, hidden flags) and the global rarity table. A routine refresh of
 * the player's unlocks reuses it instead of re-requesting both, and the Local Steam return check
 * maps local progress through it with no network at all. Stored as the provider's own JSON.
 */
@Entity(
    tableName = "achievement_metadata_cache",
    primaryKeys = ["provider", "provider_game_id"],
)
data class AchievementMetadataCacheEntity(
    val provider: String,

    @ColumnInfo(name = "provider_game_id")
    val providerGameId: String,

    @ColumnInfo(name = "schema_json")
    val schemaJson: String,

    @ColumnInfo(name = "rarity_json")
    val rarityJson: String?,

    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Long,
)
