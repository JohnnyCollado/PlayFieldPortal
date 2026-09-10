package com.playfieldportal.feature.artwork.match

import com.playfieldportal.feature.artwork.TheGamesDbApi
import com.playfieldportal.feature.artwork.api.IgdbApi
import com.playfieldportal.feature.artwork.api.ScreenScraperApi
import com.playfieldportal.feature.artwork.api.SteamGridDbApi
import com.playfieldportal.feature.artwork.rom.RomIdentity
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real provider lookups behind [GameMatcher] — C16 task 2.3.
 *
 * Retrieval only: nothing here writes a column. The Studio decides what to do with a candidate,
 * and only an explicit Change Match persists one (AD-7).
 *
 * What each provider can be asked is [ProviderCapabilities]' business, not this class's — the
 * matcher never calls a lookup a provider cannot serve, so the unsupported branches below are
 * defensive rather than load-bearing.
 */
@Singleton
class ProviderMatchEvidence @Inject constructor(
    private val steamGridDb: SteamGridDbApi,
    private val screenScraper: ScreenScraperApi,
    private val igdbApi: IgdbApi,
    private val theGamesDb: TheGamesDbApi,
) : MatchEvidenceSource {

    /**
     * ScreenScraper's game for a ROM checksum.
     *
     * The CRC alone is a weaker tuple than SS's documented hash+size+filename, but it is the only
     * part persisted on the game row (`rom_crc32`), and SS accepts it: a hit is real evidence, a
     * miss simply falls through to the next tier.
     */
    override suspend fun candidateByRomHash(
        provider: MatchProvider,
        crc32: String,
        platformId: String,
    ): GameCandidate? {
        if (provider != MatchProvider.SCREENSCRAPER) return null
        val info = runCatching {
            screenScraper.fetchGameInfo(
                platformId = platformId,
                rom = RomIdentity(crc32 = crc32, sizeBytes = null, fileName = null),
            ).info
        }.onFailure { Timber.d(it, "SS crc lookup failed for %s", crc32) }.getOrNull() ?: return null

        val id = info.ssId ?: return null
        return GameCandidate(
            provider = MatchProvider.SCREENSCRAPER,
            providerGameId = id.toString(),
            title = info.title.orEmpty().ifBlank { return null },
            releaseYear = info.releaseYear,
        )
    }

    /**
     * SteamGridDB's game for a storefront pair.
     *
     * Only the Steam half resolves: `/games/steam/{appid}` is the one direct storefront lookup the
     * API offers. An Epic, GOG or Amazon id is not silently retried as a Steam id — a cross-store
     * id is a different game, and guessing one is exactly the collision the stored PAIR exists to
     * prevent.
     */
    override suspend fun candidateByStorefront(
        provider: MatchProvider,
        storefront: String,
        storefrontGameId: String,
    ): GameCandidate? {
        if (provider != MatchProvider.STEAMGRIDDB) return null
        if (!storefront.equals("STEAM", ignoreCase = true)) return null

        val game = steamGridDb.getGameBySteamAppId(storefrontGameId) ?: return null
        return GameCandidate(
            provider = MatchProvider.STEAMGRIDDB,
            providerGameId = game.id.toString(),
            title = game.name,
        )
    }

    /**
     * Multi-result title search: SteamGridDB's autocomplete, IGDB's `search`, TheGamesDB's
     * `ByGameName` and ScreenScraper's `jeuRecherche`. These back Tier 3 and Change Match; a saved
     * id or ROM checksum still wins first (Tiers 1-2).
     *
     * [platformId] scopes TheGamesDB (`filter[platform]`) and ScreenScraper (`systemeid`) when the
     * platform is mapped. SGDB indexes games rather than platform releases, and the tree has no IGDB
     * platform-id table, so those two search every platform. The matcher establishes uniqueness on
     * the returned list.
     */
    override suspend fun searchByTitle(
        provider: MatchProvider,
        query: String,
        platformId: String,
    ): List<GameCandidate> = when (provider) {
        MatchProvider.STEAMGRIDDB -> steamGridDb.searchGame(query)
            .onFailure { Timber.d(it, "SGDB search failed for '%s'", query) }
            .getOrDefault(emptyList())
            .map {
                GameCandidate(
                    provider = MatchProvider.STEAMGRIDDB,
                    providerGameId = it.id.toString(),
                    title = it.name,
                    releaseYear = it.releaseDate?.let(::yearOf),
                )
            }
        // IgdbApi already rethrows cancellation and maps every other failure to an empty list.
        MatchProvider.IGDB -> igdbApi.searchGames(query).mapNotNull { game ->
            val name = game.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            GameCandidate(
                provider = MatchProvider.IGDB,
                providerGameId = game.id.toString(),
                title = name,
                releaseYear = game.firstReleaseDate?.let(::yearOf),
                thumbUrl = game.cover?.imageId?.let { IgdbApi.coverThumbUrl(it) },
            )
        }
        // TheGamesDbApi rethrows cancellation and maps every other failure to an empty list too.
        MatchProvider.THEGAMESDB -> theGamesDb.searchGames(platformId, query).mapNotNull { game ->
            val title = game.gameTitle.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            GameCandidate(
                provider = MatchProvider.THEGAMESDB,
                providerGameId = game.id.toString(),
                title = title,
                releaseYear = game.releaseDate?.take(4)?.toIntOrNull(),
            )
        }
        // The only route to a ScreenScraper identity for a game with no ROM file (a Windows install).
        MatchProvider.SCREENSCRAPER -> screenScraper.searchGames(platformId, query).map { hit ->
            GameCandidate(
                provider = MatchProvider.SCREENSCRAPER,
                providerGameId = hit.ssId.toString(),
                title = hit.title,
                releaseYear = hit.releaseYear,
            )
        }
    }

    /** Both SGDB and IGDB serve release dates as unix timestamps in seconds. */
    private fun yearOf(epochSeconds: Long): Int =
        java.time.Instant.ofEpochSecond(epochSeconds).atZone(java.time.ZoneOffset.UTC).year
}
