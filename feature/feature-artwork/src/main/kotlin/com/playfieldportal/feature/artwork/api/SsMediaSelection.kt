package com.playfieldportal.feature.artwork.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The single source of the `medias[]` → per-kind URL selection rules, shared by three
 * consumers so they can never drift: the live `jeuInfos` parse, the ss_media_cache read path
 * (scrapes that skip the metadata call), and the Artwork Studio's browse grid.
 */
@Serializable
data class SsCachedMedia(
    val type: String,
    val region: String? = null,
    val url: String? = null,
    val format: String? = null,
)

/** Per-kind winners resolved from a medias list — mirrors SsGameInfo's URL fields. */
data class SsMediaUrls(
    val artworkUrl: String?,
    val boxArtUrl: String?,
    val box3dUrl: String?,
    val physicalMediaUrl: String?,
    val screenshotUrl: String?,
    val heroUrl: String?,
    val logoUrl: String?,
    val manualUrl: String?,
    val videoUrl: String?,
    val videoRawUrl: String?,
)

object SsMediaSelection {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val listSerializer = ListSerializer(SsCachedMedia.serializer())

    /** Best URL of [type]: prefer region=us, fall back to wor/none/any. */
    fun bestUrl(medias: List<SsCachedMedia>, type: String): String? = medias
        .filter { it.type == type && it.url != null }
        .sortedWith(compareBy { when (it.region) { "us" -> 0; "wor" -> 1; null -> 2; else -> 3 } })
        .firstOrNull()?.url

    /** Resolves every kind's winner with the canonical type/fallback preferences. */
    fun urls(medias: List<SsCachedMedia>): SsMediaUrls {
        val box2d = bestUrl(medias, "box-2D")
        val box3d = bestUrl(medias, "box-3D")
        return SsMediaUrls(
            artworkUrl  = box2d ?: box3d,
            boxArtUrl   = box2d,
            box3dUrl    = box3d,
            physicalMediaUrl = bestUrl(medias, "support-2D") ?: bestUrl(medias, "support-texture"),
            screenshotUrl = bestUrl(medias, "ss"),
            heroUrl     = bestUrl(medias, "fanart") ?: bestUrl(medias, "ss"),
            logoUrl     = bestUrl(medias, "wheel") ?: bestUrl(medias, "wheel-hd"),
            manualUrl   = bestUrl(medias, "manuel"),
            videoUrl    = bestUrl(medias, "video-normalized"),
            videoRawUrl = bestUrl(medias, "video"),
        )
    }

    /**
     * An [SsGameInfo] carrying only URLs (all text fields null), built from a cached medias
     * list — the cache-hit scrape path. COALESCE persistence means the null text fields never
     * clobber what a real jeuInfos already stored.
     */
    fun infoFromCache(ssId: Long, medias: List<SsCachedMedia>): SsGameInfo {
        val u = urls(medias)
        return SsGameInfo(
            ssId = ssId,
            title = null, description = null, developer = null, publisher = null,
            releaseYear = null, genre = null, players = null, ageRating = null,
            franchise = null, communityRating = null, releaseDate = null,
            artworkUrl = u.artworkUrl, boxArtUrl = u.boxArtUrl, box3dUrl = u.box3dUrl,
            physicalMediaUrl = u.physicalMediaUrl, screenshotUrl = u.screenshotUrl,
            heroUrl = u.heroUrl, logoUrl = u.logoUrl, manualUrl = u.manualUrl,
            videoUrl = u.videoUrl, videoRawUrl = u.videoRawUrl,
            medias = medias,
        )
    }

    fun encode(medias: List<SsCachedMedia>): String = json.encodeToString(listSerializer, medias)

    fun decode(text: String): List<SsCachedMedia>? =
        runCatching { json.decodeFromString(listSerializer, text) }.getOrNull()?.takeIf { it.isNotEmpty() }
}
