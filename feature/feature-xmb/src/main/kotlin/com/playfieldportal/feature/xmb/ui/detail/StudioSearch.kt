package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.feature.artwork.match.TitleKey
import com.playfieldportal.feature.artwork.store.ArtworkKind

/**
 * The Artwork Studio's search model — pure, so the whole of C16 Phase 1's correctness (what a
 * query means, when two requests are the same request, which response may reach the screen) is
 * unit-testable without a ViewModel, a coroutine or a provider.
 */

/**
 * Normalization used for CACHE KEYING and matching only.
 *
 * It never touches what the user typed: the editable field always holds their exact text, and
 * the game is never renamed by searching. Normalizing is purely so that "Final Fantasy VII",
 * "  final   fantasy vii  " and "Final Fantasy VII (USA)" hit one cache entry instead of three.
 */
object StudioQuery {

    /**
     * The key form of [raw]. Delegates to [TitleKey] so the query that addresses a result cache
     * and the title that resolves a Phase 2 match can never drift apart (task 2.2).
     */
    fun normalize(raw: String): String = TitleKey.of(raw)

    /** True when [a] and [b] address the same results — the test behind "do I need to refetch?". */
    fun sameQuery(a: String, b: String): Boolean = TitleKey.same(a, b)
}

/**
 * Everything that decides WHICH results a request produces, and nothing that does not.
 *
 * Two requests with equal keys are the same request, so one may serve the other from cache; two
 * with different keys are different requests, so a response for one may never reduce into the
 * other's state. That equality is the whole race fix (AD-6): the disappearing-artwork bug was a
 * single shared result list written by whichever unkeyed job happened to finish last.
 *
 * [sgdb] is what SteamGridDB is asked to leave out (Mature, Humor, Epilepsy Warning, Animation). It
 * is part of the key, but only SteamGridDB's: every other source's key carries null, so changing one
 * of those filters can never invalidate ScreenScraper's or IGDB's cached pages (task 1.3). Filters
 * applied to a fetched list (Style, Dimensions, Region, Media) are not in any key: the cached list
 * stays whole and is filtered as it is shown.
 *
 * [matchId] is the confirmed game match the results were fetched for. It is always null today;
 * Phase 2's tiered matcher fills it, and because it is already in the key, changing the match
 * will invalidate exactly the right cache entries without touching this class.
 */
data class StudioRequestKey(
    val normalizedQuery: String,
    val source: StudioSource,
    val kind: ArtworkKind,
    val sgdb: SgdbRequestFilter? = null,
    val matchId: String? = null,
) {
    companion object {
        /** Builds the key for a browse, applying the source-scoping rules above. */
        fun of(
            query: String,
            source: StudioSource,
            kind: ArtworkKind,
            sgdb: SgdbRequestFilter,
            matchId: String? = null,
        ) = StudioRequestKey(
            normalizedQuery = StudioQuery.normalize(query),
            source = source,
            kind = kind,
            // What SteamGridDB is asked to leave out is nothing else's business.
            sgdb = sgdb.takeIf { source == StudioSource.STEAMGRIDDB },
            matchId = matchId,
        )
    }
}

/**
 * Which asset a result tile is, for selection (C16 task 5.1): the destination [kind], the
 * [provider], and the provider's own asset id or, when it has none, the URL.
 *
 * Never a grid position: a page is one measured gridful, so an index names a different tile after
 * paging, a re-page or a source switch. The kind is part of it because one provider asset is offered
 * on several tabs (SteamGridDB grids on ICON0, BOX ART and SCREENSHOT), and picking it for one
 * destination is not picking it for another.
 */
data class StudioArtKey(val kind: ArtworkKind, val provider: String, val asset: String) {
    companion object {
        fun of(kind: ArtworkKind, art: StudioArt) = StudioArtKey(kind, art.provider, art.providerAssetId ?: art.url)
    }
}

/**
 * ScreenScraper's asset id, read out of a `mediaJeu.php` URL as `<jeuid>:<media>`.
 *
 * Media URLs are kept exactly as ScreenScraper served them, and those can carry the developer and
 * user credentials as query parameters. As a key such a URL would change with the account and hold a
 * password, so the game id and media name are the identity instead. Null when either is missing, in
 * which case the URL is used as it is.
 */
object ScreenScraperAssetId {
    fun of(url: String?): String? {
        val query = url?.substringAfter('?', missingDelimiterValue = "")?.takeIf { it.isNotEmpty() } ?: return null
        val params = query.split('&').mapNotNull { pair ->
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', missingDelimiterValue = "")
            if (name.isEmpty() || value.isEmpty()) return@mapNotNull null
            runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrNull()?.let { name.lowercase() to it }
        }.toMap()
        val gameId = params["jeuid"] ?: return null
        val media = params["media"] ?: return null
        return "$gameId:$media"
    }
}

/**
 * ScreenScraper media as Studio tiles for [kind]: one tile per file, in [types] order.
 *
 * `jeuInfos` lists some files more than once, either the same entry twice or one file under several
 * regions (screenmarquee for wor, uk and us all serving `media=screenmarquee(wor)`), and
 * ss_media_cache keeps the list as served. Picks are keyed by asset, so each copy would be its own
 * tile that is picked and unpicked with the others. The one tile's label names every region its file
 * was listed under.
 */
internal fun screenScraperTiles(
    kind: ArtworkKind,
    types: List<String>,
    medias: List<com.playfieldportal.feature.artwork.api.SsCachedMedia>,
): List<StudioArt> {
    val served = types.flatMap { type ->
        medias.mapNotNull { media -> media.url?.takeIf { media.type == type }?.let { url -> media to url } }
    }
    return served
        .groupBy { (_, url) -> ScreenScraperAssetId.of(url) ?: url }
        .values
        .map { copies ->
            val (media, url) = copies.first()
            val regions = copies.mapNotNull { (copy, _) -> copy.region?.uppercase() }.distinct()
            StudioArt(
                url = url,
                thumb = null,
                provider = "ScreenScraper",
                label = listOfNotNull(media.type, regions.joinToString("/").ifEmpty { null }).joinToString(" · "),
                isVideo = kind == ArtworkKind.VIDEO || kind == ArtworkKind.ICON1,
                providerAssetId = ScreenScraperAssetId.of(url),
                facets = StudioArtFacets(
                    mediaType = media.type,
                    regions = copies.mapNotNull { (copy, _) -> copy.region?.lowercase() }.distinct(),
                ),
            )
        }
}

/**
 * Steam's store media as tiles for [kind]. Each tab gets the asset Steam made for that shape where
 * there is one (library capsule for Box Art, library hero for Hero, logo for Logo); ICON0 is
 * cropped from the landscape art; the show-all tabs get everything, screenshots first on the
 * Screenshot tab. [StudioArt.providerAssetId] is the app plus the asset's role, so the same file
 * reads as the same asset on every tab.
 */
internal fun steamTiles(kind: ArtworkKind, media: com.playfieldportal.feature.artwork.api.SteamStoreMedia): List<StudioArt> {
    fun tile(role: String, url: String?, label: String) =
        url?.let { StudioArt(url = it, thumb = null, provider = STEAM_ART, label = label, providerAssetId = "${media.appId}:$role") }
    val capsule = tile("library_capsule", media.libraryCapsule, "library capsule")
    val hero = tile("library_hero", media.libraryHero, "library hero")
    val logo = tile("logo", media.logo, "logo")
    val header = tile("header", media.header, "header")
    val mainCapsule = tile("main_capsule", media.mainCapsule, "store capsule")
    val background = tile("page_background", media.pageBackground, "store background")
    val shots = media.screenshots.mapIndexed { index, shot ->
        StudioArt(
            url = shot.url, thumb = shot.thumb, provider = STEAM_ART, label = "screenshot ${index + 1}",
            providerAssetId = "${media.appId}:ss:${shot.url.substringAfterLast('/').substringBefore('?')}",
        )
    }
    val stills = listOfNotNull(capsule, hero, header, mainCapsule, background, logo)
    return when (kind) {
        ArtworkKind.ICON           -> listOfNotNull(header, mainCapsule, hero)
        ArtworkKind.BOX_ART        -> listOfNotNull(capsule)
        ArtworkKind.HERO           -> listOfNotNull(hero)
        ArtworkKind.BACKGROUND     -> listOfNotNull(hero, background) + shots
        ArtworkKind.LOGO           -> listOfNotNull(logo)
        ArtworkKind.SCREENSHOT     -> shots + stills
        ArtworkKind.BOX_3D,
        ArtworkKind.PHYSICAL_MEDIA -> stills + shots
        // Both video tabs list both cuts (user decision, 2026-09-29). The store download turns the
        // full stream into a 720p mp4 on Video and a silent first-minute snap on ICON1.
        ArtworkKind.ICON1,
        ArtworkKind.VIDEO          -> media.trailers.flatMap { trailer ->
            val id = "${media.appId}:trailer:${trailer.id}"
            listOfNotNull(
                trailer.fullUrl?.let {
                    StudioArt(it, trailer.poster, STEAM_ART, trailer.name, isVideo = true, providerAssetId = id)
                },
                trailer.shortUrl?.let {
                    StudioArt(it, trailer.poster, STEAM_ART, "${trailer.name} · short", isVideo = true, providerAssetId = "$id:short")
                },
            )
        }
        else                       -> emptyList()
    }
}

private const val STEAM_ART = "Steam"

/**
 * [StudioArt.provider] of a tile that stands for a file the slot already stores, not for a provider's
 * asset. Local File has nothing to browse, so on a multi-asset tab its grid is the slot itself: every
 * stored asset, whatever it came from, so one checklist can remove any of them.
 */
const val LOCAL_ART = "local_art"

/**
 * [library]'s stored assets as [LOCAL_ART] tiles, in position order. The URL is the stored file's own
 * URI: what the tile draws, and what [StudioLibraryAssets.holds] matches it back to its slot by.
 * Empty while [library] still describes another kind, since a tab switch re-reads it a moment later.
 */
internal fun localArtTiles(kind: ArtworkKind, library: StudioLibraryAssets): List<StudioArt> =
    if (library.kind != kind) emptyList()
    else library.slots.map { slot ->
        StudioArt(
            url = slot.documentUri,
            thumb = slot.documentUri,
            provider = LOCAL_ART,
            label = slot.provider,
            isVideo = kind == ArtworkKind.VIDEO,
        )
    }

/**
 * What one multi-asset slot already holds (found on device during task 5.2; the queue's own states last
 * one open). A tile it holds starts checked, and unchecking it marks the stored asset for removal.
 *
 * A tile is held when a stored record has its provider asset id, or was downloaded from its URL. A
 * record written by Apply has no asset id, only the URL, and ScreenScraper URLs are stored as served,
 * credentials included, so those are compared by [ScreenScraperAssetId]. Providers are not compared:
 * asset ids and URLs never coincide across providers, and the scraper and the Studio name them apart.
 */
data class StudioLibraryAssets(
    val kind: ArtworkKind? = null,
    val slots: List<com.playfieldportal.feature.artwork.store.StudioArtworkSlot> = emptyList(),
) {
    fun holds(kind: ArtworkKind, art: StudioArt): Boolean = kind == this.kind && slots.any { it.holds(art) }

    /** Every position holding [art]'s asset: one, or more if it was stored twice. */
    fun sortOrdersHolding(art: StudioArt): List<Int> = slots.filter { it.holds(art) }.map { it.sortOrder }

    companion object {
        fun of(kind: ArtworkKind, slots: List<com.playfieldportal.feature.artwork.store.StudioArtworkSlot>) =
            StudioLibraryAssets(kind, slots)

        // A LOCAL_ART tile is one stored file, so it is held by that file and by nothing else.
        private fun com.playfieldportal.feature.artwork.store.StudioArtworkSlot.holds(art: StudioArt): Boolean =
            if (art.provider == LOCAL_ART) documentUri == art.url
            else (providerAssetId != null && providerAssetId == art.providerAssetId) ||
                originUrl?.let(::originOf) == originOf(art.url)

        private fun originOf(url: String): String = ScreenScraperAssetId.of(url) ?: url
    }
}

/**
 * A small LRU of finished result lists, keyed by [StudioRequestKey].
 *
 * Replaces the single `allResults` field: with one list per key, switching back to a source the
 * user already visited is instant and — more importantly — a late response can only ever be
 * stored under its OWN key, never on top of what is currently on screen.
 */
class StudioResultCache(private val maxEntries: Int = MAX_ENTRIES) {

    private val entries = LinkedHashMap<StudioRequestKey, List<StudioArt>>(16, 0.75f, true)

    operator fun get(key: StudioRequestKey): List<StudioArt>? = synchronized(entries) { entries[key] }

    operator fun set(key: StudioRequestKey, results: List<StudioArt>) = synchronized(entries) {
        entries[key] = results
        while (entries.size > maxEntries) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    fun contains(key: StudioRequestKey): Boolean = synchronized(entries) { entries.containsKey(key) }

    /** Drops every entry for [source] — used when its credentials or filters change underneath it. */
    fun evictSource(source: StudioSource) = synchronized(entries) {
        entries.keys.filter { it.source == source }.forEach { entries.remove(it) }
    }

    fun clear() = synchronized(entries) { entries.clear() }

    val size: Int get() = synchronized(entries) { entries.size }

    private companion object {
        // A gridful each, across the handful of category/source pairs a session actually visits.
        const val MAX_ENTRIES = 24
    }
}

/**
 * One page of results, computed from a full list — the client-side paging every provider forces
 * on us (AD-3: none of them support server paging) and a page is exactly one gridful (AD-5).
 */
data class StudioPage(
    val items: List<StudioArt>,
    val pageIndex: Int,
    val pageCount: Int,
    /** 1-based inclusive range of [items] within the whole result list; 0..0 when empty. */
    val rangeStart: Int,
    val rangeEnd: Int,
    val totalResults: Int,
) {
    val hasPrevious: Boolean get() = pageIndex > 0
    val hasNext: Boolean get() = pageIndex < pageCount - 1

    companion object {
        fun of(all: List<StudioArt>, pageIndex: Int, pageSize: Int): StudioPage {
            require(pageSize > 0) { "pageSize must be positive" }
            val pageCount = if (all.isEmpty()) 0 else (all.size + pageSize - 1) / pageSize
            val clamped = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            val from = clamped * pageSize
            val items = if (all.isEmpty()) emptyList() else all.drop(from).take(pageSize)
            return StudioPage(
                items = items,
                pageIndex = clamped,
                pageCount = pageCount,
                rangeStart = if (items.isEmpty()) 0 else from + 1,
                rangeEnd = if (items.isEmpty()) 0 else from + items.size,
                totalResults = all.size,
            )
        }
    }
}
