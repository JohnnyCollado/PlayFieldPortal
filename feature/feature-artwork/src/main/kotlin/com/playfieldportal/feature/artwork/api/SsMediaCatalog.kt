package com.playfieldportal.feature.artwork.api

import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.dao.SsMediaCacheDao
import com.playfieldportal.core.data.database.entity.SsMediaCacheEntity
import com.playfieldportal.feature.artwork.rom.RomHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scrape-as-you-go access to a game's ScreenScraper media list: cached lists load instantly,
 * anything else triggers ONE live `jeuInfos` right here — no "scrape the game first" chore.
 * A successful live lookup pays forward: the medias land in ss_media_cache, a fresh ssId match
 * is persisted on the game row, and missing text metadata is filled (COALESCE — never
 * overwrites), so the next open is a pure cache hit and the scraper skips this game's call too.
 *
 * Serialized by a single mutex: concurrent callers (rapid tab switches) queue instead of
 * burning duplicate API calls; the second caller re-checks the cache the first one just wrote.
 */
@Singleton
class SsMediaCatalog @Inject constructor(
    private val screenScraper: ScreenScraperApi,
    private val ssMediaCacheDao: SsMediaCacheDao,
    private val gameDao: GameDao,
    private val romHasher: RomHasher,
) {
    private val inFlight = Mutex()

    /**
     * The game's full SS media list, from cache or one live lookup. Null = no match/SS off.
     *
     * [matchedSsId] browses a game the Studio's matcher resolved by TITLE. That game's medias are
     * fetched and cached, but its id is never written to the game row: a title match is not an
     * identity, and persisting it would turn a guess into Tier 1 evidence. Only the ROM-identity
     * lookup below — or a Change Match the user confirms — sets `ss_id`.
     */
    suspend fun mediasFor(gameId: Long, matchedSsId: Long? = null): List<SsCachedMedia>? = inFlight.withLock {
        withContext(Dispatchers.IO) {
            val game = gameDao.getById(gameId) ?: return@withContext null

            if (matchedSsId != null && matchedSsId != game.ssId) {
                ssMediaCacheDao.get(matchedSsId)?.let { row ->
                    SsMediaSelection.decode(row.mediasJson)?.let { return@withContext it }
                }
                if (!screenScraper.isEnabled()) return@withContext null
                val info = screenScraper.fetchGameInfo(game.platformId, rom = null, ssGameId = matchedSsId).info
                    ?: return@withContext null
                if (info.medias.isNotEmpty()) {
                    ssMediaCacheDao.upsert(
                        SsMediaCacheEntity(matchedSsId, SsMediaSelection.encode(info.medias), System.currentTimeMillis())
                    )
                }
                Timber.i("SS catalog lookup for title-matched ssId=$matchedSsId (gameId=$gameId, not persisted) → ${info.medias.size} medias")
                return@withContext info.medias
            }

            // Old links: straight from cache.
            game.ssId?.let { id ->
                ssMediaCacheDao.get(id)?.let { row ->
                    SsMediaSelection.decode(row.mediasJson)?.let { return@withContext it }
                }
            }

            // New links: one live lookup. Known id fetches directly; otherwise match by ROM
            // identity (the hasher caps oversized ROMs itself, so this can't stall on a 4 GB iso).
            if (!screenScraper.isEnabled()) return@withContext null
            val rom = if (game.ssId == null) romHasher.identify(game.romPath, game.romUri) else null
            val info = screenScraper.fetchGameInfo(game.platformId, rom, game.ssId).info
                ?: return@withContext null

            val matchedId = info.ssId
            if (matchedId != null && info.medias.isNotEmpty()) {
                ssMediaCacheDao.upsert(
                    SsMediaCacheEntity(matchedId, SsMediaSelection.encode(info.medias), System.currentTimeMillis())
                )
            }
            // The lookup doubles as a mini-scrape: persist the match id and fill-missing text
            // metadata so this game never needs the jeuInfos call again anywhere.
            gameDao.updateMetadata(
                id = gameId,
                description = info.description,
                developer = info.developer,
                publisher = info.publisher,
                releaseYear = info.releaseYear,
                genre = info.genre,
                scrapedTitle = if (game.userTitleOverride == null) info.title else null,
                players = info.players,
                ageRating = info.ageRating,
                franchise = info.franchise,
                communityRating = info.communityRating,
                releaseDate = info.releaseDate,
                ssId = matchedId,
                romCrc32 = rom?.crc32,
            )
            Timber.i("SS catalog live lookup for gameId=$gameId → ssId=$matchedId, ${info.medias.size} medias")
            info.medias
        }
    }
}
