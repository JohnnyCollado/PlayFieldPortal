package com.playfieldportal.feature.achievements.provider.steam

import com.playfieldportal.core.data.database.dao.AchievementTrackingDao
import com.playfieldportal.core.data.database.entity.AchievementMetadataCacheEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** A Steam app's cached achievement metadata: the schema and the global rarity table. */
data class SteamMetadata(
    val schema: List<SteamSchemaAchievement>,
    val rarity: Map<String, Double>,
    val fetchedAt: Long,
)

/**
 * Steam schema + rarity cached apart from player unlocks (plan section 4, Steam 2). Shared by the
 * STEAM and LOCAL_STEAM providers — both key on the same appid — so a Local Steam return check can
 * map fresh local progress with no network request at all.
 */
@Singleton
class SteamMetadataStore @Inject constructor(
    private val dao: AchievementTrackingDao,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val schemaSerializer = ListSerializer(SteamSchemaAchievement.serializer())
    private val raritySerializer = MapSerializer(String.serializer(), Double.serializer())

    suspend fun get(appId: String): SteamMetadata? {
        val row = dao.getMetadata(KEY, appId) ?: return null
        return runCatching {
            SteamMetadata(
                schema = json.decodeFromString(schemaSerializer, row.schemaJson),
                rarity = row.rarityJson?.let { json.decodeFromString(raritySerializer, it) }.orEmpty(),
                fetchedAt = row.fetchedAt,
            )
        }.onFailure { Timber.w(it, "Unreadable cached Steam metadata for %s", appId) }.getOrNull()
    }

    suspend fun put(appId: String, metadata: SteamMetadata) {
        dao.upsertMetadata(
            AchievementMetadataCacheEntity(
                provider = KEY,
                providerGameId = appId,
                schemaJson = json.encodeToString(schemaSerializer, metadata.schema),
                rarityJson = json.encodeToString(raritySerializer, metadata.rarity),
                fetchedAt = metadata.fetchedAt,
            ),
        )
    }

    private companion object {
        // One cache for STEAM and LOCAL_STEAM: the schema belongs to the appid, not the copy.
        const val KEY = "STEAM"
    }
}
