package com.playfieldportal.feature.artwork.store

import android.content.Context
import android.net.Uri
import coil.imageLoader
import coil.memory.MemoryCache
import com.playfieldportal.core.data.database.dao.ArtworkRecordDao
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.ArtworkRecordEntity
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.repository.ArtworkFolderRepository
import com.playfieldportal.feature.artwork.portable.ArtworkPathResolver
import com.playfieldportal.feature.artwork.portable.PortableArtworkLibrary
import com.playfieldportal.feature.artwork.portable.PortableNameResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app-wide [ArtworkStore]: routes every save into the user's portable media library when a
 * folder is linked, and falls back to [InternalArtworkStore] otherwise — callers (scraper,
 * detail-screen pickers) are unchanged either way.
 *
 * Conflict policy (spec §22) is enforced here for the portable side:
 *  • auto-scrapes ([saveFromUrl]) never overwrite an existing valid library asset — existing
 *    portable artwork outranks newly scraped; the existing reference is returned instead.
 *  • user picks ([saveVersionedFromUrl]/[saveVersionedFromUri]) always write and mark the
 *    record `user_assigned + locked`, so no automatic pass touches that slot again.
 *  • [deleteAll] clears app state (internal files + records) but NEVER deletes files in the
 *    user's folder — the library is user-owned; Relink can always reconnect it.
 *
 * Portable names are stable ("{ROM stem}.png"), so replacing bytes keeps the same content URI;
 * Coil's caches are invalidated for that URI on every portable write to make changes visible.
 */
@Singleton
class RoutingArtworkStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val internal: InternalArtworkStore,
    private val library: PortableArtworkLibrary,
    private val folderRepository: ArtworkFolderRepository,
    private val gameDao: GameDao,
    private val artworkRecordDao: ArtworkRecordDao,
    private val httpClient: HttpClient,
) : ArtworkStore {

    override suspend fun saveFromUrl(gameId: Long, kind: ArtworkKind, url: String): String? {
        val target = portableTarget(gameId) ?: return internal.saveFromUrl(gameId, kind, url)
        val (tree, game) = target

        // Existing portable artwork outranks a new auto-scrape (§22) — including locked and
        // user-assigned assets. Dead files fall through and are replaced.
        artworkRecordDao.get(gameId, kind.name)?.let { record ->
            if (record.locked || record.userAssigned || internal.isValidRef(record.documentUri)) {
                return record.documentUri
            }
        }
        val tmp = ArtworkTempIO.downloadToTemp(httpClient, context.cacheDir, kind, url) ?: return null
        return persistPortable(tree, game, kind, tmp, source = SOURCE_SCRAPE, userAssigned = false)
    }

    override suspend fun saveVersionedFromUrl(gameId: Long, kind: ArtworkKind, url: String): String? {
        val target = portableTarget(gameId) ?: return internal.saveVersionedFromUrl(gameId, kind, url)
        val (tree, game) = target
        val tmp = ArtworkTempIO.downloadToTemp(httpClient, context.cacheDir, kind, url) ?: return null
        return persistPortable(tree, game, kind, tmp, source = SOURCE_USER, userAssigned = true)
    }

    override suspend fun saveVersionedFromUri(gameId: Long, kind: ArtworkKind, uri: Uri): String? {
        val target = portableTarget(gameId) ?: return internal.saveVersionedFromUri(gameId, kind, uri)
        val (tree, game) = target
        val tmp = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    ArtworkTempIO.copyToTemp(it, context.cacheDir, kind)
                }
            }.onFailure { Timber.e(it, "Failed to read picked artwork $uri") }.getOrNull()
        } ?: return null
        return persistPortable(tree, game, kind, tmp, source = SOURCE_USER, userAssigned = true)
    }

    override fun isValidRef(ref: String?): Boolean = internal.isValidRef(ref)

    override suspend fun find(gameId: Long, kind: ArtworkKind): String? =
        internal.find(gameId, kind)
            ?: artworkRecordDao.get(gameId, kind.name)?.documentUri?.takeIf { internal.isValidRef(it) }

    override suspend fun deleteAll() {
        internal.deleteAll()
        // Records are app state; the library files are the user's and are never deleted here.
        artworkRecordDao.clear()
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private suspend fun portableTarget(gameId: Long): Pair<Uri, GameEntity>? {
        val treeUri = folderRepository.getTreeUri() ?: return null
        if (!folderRepository.hasLiveGrant()) return null
        val game = gameDao.getById(gameId) ?: return null
        return Uri.parse(treeUri) to game
    }

    private suspend fun persistPortable(
        tree: Uri,
        game: GameEntity,
        kind: ArtworkKind,
        tempFile: java.io.File,
        source: String,
        userAssigned: Boolean,
    ): String? {
        val romFileName = game.romPath?.replace('\\', '/')?.substringAfterLast('/')
        var portableName = romFileName?.let { PortableNameResolver.fromRomFileName(it) }
            ?: PortableNameResolver.fromTitle(game.userTitleOverride ?: game.scrapedTitle ?: game.title)
        if (artworkRecordDao.findNameCollisions(game.platformId, kind.name, portableName, game.id).isNotEmpty()) {
            portableName = "$portableName (2)"
        }
        val saved = library.saveFromFile(tree, game.platformId, kind, portableName, tempFile) ?: return null
        artworkRecordDao.upsert(
            ArtworkRecordEntity(
                gameId = game.id,
                platformId = game.platformId,
                artworkType = kind.name,
                portableName = portableName,
                relativePath = ArtworkPathResolver.relativePath(game.platformId, kind, saved.fileName),
                documentUri = saved.uriString,
                source = source,
                sizeBytes = saved.sizeBytes,
                userAssigned = userAssigned,
                locked = userAssigned,
            )
        )
        // Stable names → stable URIs: bust Coil so the replacement is visible immediately.
        context.imageLoader.memoryCache?.remove(MemoryCache.Key(saved.uriString))
        context.imageLoader.diskCache?.remove(saved.uriString)
        return saved.uriString
    }

    private companion object {
        const val SOURCE_SCRAPE = "scrape"
        const val SOURCE_USER = "user"
    }
}
