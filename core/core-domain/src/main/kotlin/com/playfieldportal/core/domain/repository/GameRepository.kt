package com.playfieldportal.core.domain.repository

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.PlaySession
import com.playfieldportal.core.domain.model.RecentPlatform
import kotlinx.coroutines.flow.Flow

interface GameRepository {
    fun observeAll(): Flow<List<Game>>
    fun observeFavorites(): Flow<List<Game>>
    fun observeByPlatform(platformId: String): Flow<List<Game>>
    fun observeRecentPlatforms(limit: Int): Flow<List<RecentPlatform>>
    suspend fun getById(id: Long): Game?
    suspend fun getByPackageName(packageName: String): Game?
    suspend fun upsert(game: Game): Long
    suspend fun delete(id: Long)
    suspend fun setFavorite(id: Long, isFavorite: Boolean)
    suspend fun updateFavoriteSortOrder(id: Long, order: Int)
    suspend fun updateNote(id: Long, note: String?)
    suspend fun updateBoxArt(id: Long, uri: String?)
    suspend fun updateHeroArt(id: Long, uri: String?)
    suspend fun updateLogoArt(id: Long, uri: String?)
    suspend fun updateIconArt(id: Long, uri: String?)
    suspend fun setPreferredEmulator(id: Long, profileIdOrPackage: String?)
    suspend fun recordPlaySession(session: PlaySession)
    suspend fun getMissingRoms(): List<Game>
    suspend fun updateScrapedTitle(id: Long, scrapedTitle: String?)
    // Pass null to clear the override and fall back to scrapedTitle / title.
    suspend fun updateUserTitleOverride(id: Long, override: String?)
}
