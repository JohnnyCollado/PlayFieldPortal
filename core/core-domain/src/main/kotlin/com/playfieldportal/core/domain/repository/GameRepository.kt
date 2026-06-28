package com.playfieldportal.core.domain.repository

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.PlaySession
import com.playfieldportal.core.domain.model.RecentPlatform
import kotlinx.coroutines.flow.Flow

/**
 * Read/write access to the game library — the boundary between features and the data layer.
 *
 * The `games` table holds both real games (`content_type = GAME`) and Android app-shortcut rows
 * (`content_type = ANDROID_APP`); [observeGamesOnly] / [getAppEntry] distinguish them so app
 * shortcuts never aggregate into "All Games". Flows emit on every underlying change so the UI
 * stays reactive.
 */
interface GameRepository {
    fun observeAll(): Flow<List<Game>>
    // Real games only (content_type = GAME) — drives the "All Games" aggregate so app-style
    // entries never appear there automatically.
    fun observeGamesOnly(): Flow<List<Game>>
    fun observeFavorites(): Flow<List<Game>>
    fun observeByPlatform(platformId: String): Flow<List<Game>>
    fun observeRecentPlatforms(limit: Int): Flow<List<RecentPlatform>>
    suspend fun getById(id: Long): Game?
    suspend fun getByPackageName(packageName: String): Game?
    // The "open the app" row (no launcher shortcut id).
    suspend fun getAppEntry(packageName: String): Game?
    // A specific harvested launcher-shortcut row (host package + shortcut id).
    suspend fun getLauncherShortcut(packageName: String, shortcutId: String): Game?
    // A legacy INSTALL_SHORTCUT row, deduped by its captured launch intent uri.
    suspend fun getByIntentUri(intentUri: String): Game?
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
