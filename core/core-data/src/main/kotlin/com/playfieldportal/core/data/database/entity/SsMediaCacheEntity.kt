package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached `medias` list from a ScreenScraper `jeuInfos` response, keyed by SS game id. One
 * jeuInfos call serves URLs for EVERY artwork kind — caching the list lets later scrapes of
 * newly-enabled kinds (and the Artwork Studio's browse grid) skip the metadata call entirely.
 * Pure cache: losing rows costs one extra API call per game, never data.
 */
@Entity(tableName = "ss_media_cache")
data class SsMediaCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "ss_id")
    val ssId: Long,

    // Trimmed projection of the medias array (type/region/url/format), JSON-encoded.
    @ColumnInfo(name = "medias_json")
    val mediasJson: String,

    // For staleness display/debugging only — no TTL; dead-URL fallback covers staleness.
    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Long,
)
