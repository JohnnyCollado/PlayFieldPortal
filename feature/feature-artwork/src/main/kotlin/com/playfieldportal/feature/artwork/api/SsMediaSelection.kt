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

    /**
     * The region preference walk (C22 task T6): the user's region, then the shipped default.
     *
     * The tail is deliberately `us → wor → null → …`, which is exactly the order that shipped
     * before this preference existed. ES-DE's equivalent walk leads with `wor`, but adopting that
     * would change which box art every existing install picks for a game released in both — a
     * silent re-scrape of the whole library's look, to no one's benefit. A user who wants world
     * art first can now say so, and an untouched install keeps the art it already has.
     *
     * `null` is in the list because plenty of media types carry no region attribute at all
     * (videos and fan art especially); without it those would only ever be reached by the final
     * catch-all and would lose to a worse-but-regioned entry of the same type.
     */
    private fun regionWalk(userRegion: String?): List<String?> =
        buildList {
            userRegion?.lowercase()?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(listOf("us", "wor", null, "eu", "jp", "cus"))
        }.distinct()

    /** A chosen media and how good its region was — 0 is best. */
    data class Pick(val url: String, val regionPos: Int)

    /**
     * Best media of [type], with the RANK of the region it matched at.
     *
     * The rank is the point. Two different types can stand in for one slot (a `wheel` and a
     * `wheel-hd` are both the logo), and picking between them by bare `?:` ordering means a
     * plain `wheel` from a region nobody wanted beats a `wheel-hd` from the user's own. Returning
     * where each one matched lets [urls] compare them on the axis that actually matters.
     *
     * An entry whose region is in neither the walk nor null still wins if it is all there is —
     * it ranks last, after every named fallback.
     */
    fun bestMedia(medias: List<SsCachedMedia>, type: String, userRegion: String? = null): Pick? {
        val candidates = medias.filter { it.type == type && it.url != null }
        if (candidates.isEmpty()) return null
        val walk = regionWalk(userRegion)
        walk.forEachIndexed { index, region ->
            candidates.firstOrNull { it.region?.lowercase() == region }
                ?.let { return Pick(it.url!!, index) }
        }
        // Released only in a region nobody listed. Better than showing the game no art at all.
        return candidates.first().url?.let { Pick(it, walk.size) }
    }

    /** Best URL of [type] for [userRegion], discarding the rank. */
    fun bestUrl(medias: List<SsCachedMedia>, type: String, userRegion: String? = null): String? =
        bestMedia(medias, type, userRegion)?.url

    /** Whichever of [a] and [b] matched at a better region; [a] wins a tie. */
    private fun better(a: Pick?, b: Pick?): String? = when {
        a == null -> b?.url
        b == null -> a.url
        b.regionPos < a.regionPos -> b.url
        else -> a.url
    }

    /** Resolves every kind's winner with the canonical type/fallback preferences. */
    fun urls(medias: List<SsCachedMedia>, userRegion: String? = null): SsMediaUrls {
        fun pick(type: String) = bestMedia(medias, type, userRegion)
        val box2d = pick("box-2D")
        val box3d = pick("box-3D")
        return SsMediaUrls(
            // A 2D box is the intended art; a 3D one only stands in when there is none, so this
            // stays an ordered fallback rather than a region comparison.
            artworkUrl  = box2d?.url ?: box3d?.url,
            boxArtUrl   = box2d?.url,
            box3dUrl    = box3d?.url,
            physicalMediaUrl = pick("support-2D")?.url ?: pick("support-texture")?.url,
            screenshotUrl = pick("ss")?.url,
            heroUrl     = pick("fanart")?.url ?: pick("ss")?.url,
            // wheel and wheel-hd are the same asset at two resolutions, so the better REGION wins
            // rather than whichever type was written first.
            logoUrl     = better(pick("wheel"), pick("wheel-hd")),
            manualUrl   = pick("manuel")?.url,
            videoUrl    = pick("video-normalized")?.url,
            videoRawUrl = pick("video")?.url,
        )
    }

    /**
     * An [SsGameInfo] carrying only URLs (all text fields null), built from a cached medias
     * list — the cache-hit scrape path. COALESCE persistence means the null text fields never
     * clobber what a real jeuInfos already stored.
     */
    fun infoFromCache(ssId: Long, medias: List<SsCachedMedia>, userRegion: String? = null): SsGameInfo {
        val u = urls(medias, userRegion)
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
