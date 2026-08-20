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
    // Multi-disc projection (docs/plans/multi-disc-games-plan.md step 5): one row per disc set —
    // the primary — for the All Games surface. The unprojected [observeGamesOnly] stays available
    // for per-disc achievement matching.
    fun observeAllGames(): Flow<List<Game>>
    fun observeFavorites(): Flow<List<Game>>
    fun observeByPlatform(platformId: String): Flow<List<Game>>
    // Multi-disc projection: one row per disc set for a Memory Card's game list. The unprojected
    // [observeByPlatform] stays available for scan baselines (existing-path resolution needs every
    // disc row).
    fun observePlatformGames(platformId: String): Flow<List<Game>>
    // One-shot snapshot of a platform's games — for import-time dedupe checks.
    suspend fun getByPlatform(platformId: String): List<Game>
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
    // Display-mode tile columns (Artwork Studio apply path). updateBoxArt above is legacy
    // naming for the BACKGROUND column; these hit the real tile columns.
    suspend fun updateBoxArtTile(id: Long, uri: String?)
    suspend fun updatePhysicalMediaArt(id: Long, uri: String?)
    suspend fun updateBox3dArt(id: Long, uri: String?)
    // Per-game IconDisplayMode override (enum name). Pass null to follow the global setting.
    // (Note: updateBoxArt above is legacy naming for the BACKGROUND/artwork_uri column, not the
    // BOX_ART tile — the new tile columns are written by the scraper/importer, not through here.)
    suspend fun setIconDisplayMode(id: Long, mode: String?)

    // Missing-ROM tracking. markSeen / markMissing take explicit path lists the reconciler has
    // already diffed (never a whole-table sweep), so a bad or partial scan can't mass-flag the
    // library. observeMissing drives the Missing bucket UI.
    fun observeMissing(): Flow<List<Game>>
    suspend fun markSeen(romPaths: List<String>, seenAt: Long)
    suspend fun markMissing(romPaths: List<String>)
}
