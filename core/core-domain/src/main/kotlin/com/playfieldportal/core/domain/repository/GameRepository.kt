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
    suspend fun upsert(game: Game): Long
    suspend fun delete(id: Long)
    suspend fun setFavorite(id: Long, isFavorite: Boolean)
    suspend fun updateFavoriteSortOrder(id: Long, order: Int)
    suspend fun updateNote(id: Long, note: String?)
    suspend fun updateBoxArt(id: Long, uri: String?)
    suspend fun updateHeroArt(id: Long, uri: String?)
    suspend fun updateLogoArt(id: Long, uri: String?)
    suspend fun recordPlaySession(session: PlaySession)
    suspend fun getMissingRoms(): List<Game>
}
