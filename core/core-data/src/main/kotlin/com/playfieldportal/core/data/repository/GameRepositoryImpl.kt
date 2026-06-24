package com.playfieldportal.core.data.repository

import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.dao.PlaySessionDao
import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.entity.PlaySessionEntity
import com.playfieldportal.core.data.database.entity.toDomain
import com.playfieldportal.core.data.database.entity.toEntity
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.PlaySession
import com.playfieldportal.core.domain.model.RecentPlatform
import com.playfieldportal.core.domain.repository.GameRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.File
import javax.inject.Inject

class GameRepositoryImpl @Inject constructor(
    private val gameDao: GameDao,
    private val playSessionDao: PlaySessionDao,
    private val platformDao: PlatformDao,
) : GameRepository {

    override fun observeAll(): Flow<List<Game>> =
        gameDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeGamesOnly(): Flow<List<Game>> =
        gameDao.observeGamesOnly().map { entities -> entities.map { it.toDomain() } }

    override fun observeFavorites(): Flow<List<Game>> =
        gameDao.observeFavorites().map { entities -> entities.map { it.toDomain() } }

    override fun observeByPlatform(platformId: String): Flow<List<Game>> =
        gameDao.observeByPlatform(platformId).map { entities -> entities.map { it.toDomain() } }

    override fun observeRecentPlatforms(limit: Int): Flow<List<RecentPlatform>> {
        return combine(
            playSessionDao.observeRecentPlatformIds(),
            platformDao.observeAll(),
        ) { recentPlatformIds, allPlatforms ->
            val platformMap = allPlatforms.associateBy { it.id }

            recentPlatformIds.take(limit).mapNotNull { platformId ->
                val platformEntity = platformMap[platformId] ?: return@mapNotNull null
                val lastPlayedAt   = playSessionDao.getLastPlayedAt(platformId) ?: return@mapNotNull null
                val recentGameIds  = playSessionDao.getRecentGameIdsForPlatform(platformId, limit = 20)
                val recentGames    = recentGameIds.mapNotNull { gameDao.getById(it)?.toDomain() }

                RecentPlatform(
                    platform     = platformEntity.toDomain(),
                    lastPlayedAt = lastPlayedAt,
                    recentGames  = recentGames,
                )
            }
        }
    }

    override suspend fun getById(id: Long): Game? =
        gameDao.getById(id)?.toDomain()

    override suspend fun getByPackageName(packageName: String): Game? =
        gameDao.getByPackageName(packageName)?.toDomain()

    override suspend fun upsert(game: Game): Long =
        gameDao.upsert(game.toEntity())

    override suspend fun delete(id: Long) {
        gameDao.deleteById(id)
        Timber.i("Game deleted: id=$id")
    }

    override suspend fun setFavorite(id: Long, isFavorite: Boolean) =
        gameDao.setFavorite(id, isFavorite)

    override suspend fun updateFavoriteSortOrder(id: Long, order: Int) =
        gameDao.updateFavoriteSortOrder(id, order)

    override suspend fun updateNote(id: Long, note: String?) =
        gameDao.updateNote(id, note)

    override suspend fun updateBoxArt(id: Long, uri: String?) =
        gameDao.updateArtwork(id, uri)

    override suspend fun updateHeroArt(id: Long, uri: String?) =
        gameDao.updateHero(id, uri)

    override suspend fun updateLogoArt(id: Long, uri: String?) =
        gameDao.updateLogo(id, uri)

    override suspend fun updateIconArt(id: Long, uri: String?) =
        gameDao.updateIconUri(id, uri)

    override suspend fun setPreferredEmulator(id: Long, profileIdOrPackage: String?) =
        gameDao.setPreferredEmulator(id, profileIdOrPackage)

    override suspend fun recordPlaySession(session: PlaySession) {
        playSessionDao.insert(session.toEntity())
        gameDao.addPlayTime(
            id             = session.gameId,
            durationMillis = session.durationMillis,
            playedAt       = session.launchedAt,
        )
        Timber.d("Play session recorded: gameId=${session.gameId}, platform=${session.platformId}")
    }

    override suspend fun updateScrapedTitle(id: Long, scrapedTitle: String?) =
        gameDao.updateScrapedTitle(id, scrapedTitle)

    override suspend fun updateUserTitleOverride(id: Long, override: String?) =
        gameDao.updateUserTitleOverride(id, override)

    override suspend fun getMissingRoms(): List<Game> {
        val romPaths = gameDao.getAllRomPaths()
        val missingIds = romPaths
            .filter { !File(it.rom_path).exists() }
            .map { it.id }

        return missingIds.mapNotNull { gameDao.getById(it)?.toDomain() }
            .also { Timber.i("Missing ROM check: ${it.size} missing of ${romPaths.size} total") }
    }
}
