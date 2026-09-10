package com.playfieldportal.feature.artwork.store

import android.content.Context
import android.net.Uri
import com.playfieldportal.core.data.database.dao.ArtworkRecordDao
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.ArtworkRecordEntity
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.repository.ArtworkFolderRepository
import com.playfieldportal.feature.artwork.api.ArtworkImageCache
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
    private val imageCache: ArtworkImageCache,
) : ArtworkStore {

    override suspend fun saveFromUrl(gameId: Long, kind: ArtworkKind, url: String, sortOrder: Int): String? {
        val target = portableTarget(gameId) ?: return internal.saveFromUrl(gameId, kind, url, sortOrder)
        val (tree, game) = target

        // Existing portable artwork outranks a new auto-scrape (§22) — including locked and
        // user-assigned assets. Dead files fall through and are replaced.
        artworkRecordDao.getAt(gameId, kind.name, sortOrder)?.let { record ->
            if (record.locked || record.userAssigned || internal.isValidRef(record.documentUri)) {
                return record.documentUri
            }
        }
        val tmp = ArtworkTempIO.downloadToTemp(httpClient, context.cacheDir, kind, url) ?: return null
        return persistPortable(
            tree, game, kind, tmp, source = SOURCE_SCRAPE, userAssigned = false, sortOrder = sortOrder,
        )
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

    override suspend fun saveFromFile(
        gameId: Long,
        kind: ArtworkKind,
        tempFile: java.io.File,
        sortOrder: Int,
    ): String? {
        val target = portableTarget(gameId) ?: return internal.saveFromFile(gameId, kind, tempFile, sortOrder)
        val (tree, game) = target
        return persistPortable(
            tree, game, kind, tempFile, source = SOURCE_SCRAPE, userAssigned = false, sortOrder = sortOrder,
        )
    }

    override fun isValidRef(ref: String?): Boolean = internal.isValidRef(ref)

    override suspend fun find(gameId: Long, kind: ArtworkKind, sortOrder: Int): String? =
        internal.find(gameId, kind, sortOrder)
            ?: artworkRecordDao.getAt(gameId, kind.name, sortOrder)?.documentUri?.takeIf { internal.isValidRef(it) }

    /**
     * Every reference for [kind] in position order. Internal files come first (they are what
     * [find] prefers), then any recorded position the internal layout does not cover — a game is
     * normally in one mode or the other, so in practice one of the two lists is empty.
     */
    override suspend fun findAll(gameId: Long, kind: ArtworkKind): List<String> {
        val out = internal.findAll(gameId, kind).toMutableList()
        artworkRecordDao.findAll(gameId, kind.name)
            .mapNotNull { it.documentUri.takeIf { uri -> internal.isValidRef(uri) } }
            .forEach { if (it !in out) out += it }
        return out
    }

    override suspend fun deleteAll() {
        internal.deleteAll()
        // Records are app state; the library files are the user's and are never deleted here.
        artworkRecordDao.clear()
    }

    /**
     * Portable write for the internal-migration worker (M-F2): same naming/record/Coil-bust
     * discipline as a scrape, with caller-supplied provenance. Consumes [tempFile] either way.
     * Null when no folder is linked or the grant is dead.
     */
    suspend fun saveTempPortable(
        gameId: Long,
        kind: ArtworkKind,
        tempFile: java.io.File,
        source: String,
        userAssigned: Boolean,
        sortOrder: Int = 0,
    ): String? {
        val (tree, game) = portableTarget(gameId) ?: run { tempFile.delete(); return null }
        return persistPortable(tree, game, kind, tempFile, source, userAssigned, sortOrder = sortOrder)
    }

    // ── Studio pass 2 ───────────────────────────────────────────────────────────
    // Record-driven operations. They work against the portable library's artwork_records; when
    // no folder is linked there is no record, so info/restore/reset/crop return null (the Studio
    // offers only Apply + Clear in that mode). Clear itself always works.

    /** Everything the Studio's file-info panel and actions menu need, or null (no record). */
    suspend fun studioInfo(gameId: Long, kind: ArtworkKind, sortOrder: Int = 0): StudioArtworkInfo? {
        val rec = artworkRecordDao.getAt(gameId, kind.name, sortOrder) ?: return null
        return StudioArtworkInfo(
            provider = rec.provider,
            originUrl = rec.originUrl,
            relativePath = rec.relativePath,
            sizeBytes = rec.sizeBytes,
            width = rec.width,
            height = rec.height,
            source = rec.source,
            userAssigned = rec.userAssigned,
            hasPrevious = rec.prevDocumentUri != null && internal.isValidRef(rec.prevDocumentUri),
            hasOriginal = rec.hasOriginal,
            cropRect = rec.cropRect,
            updatedAt = rec.updatedAt,
            sortOrder = rec.sortOrder,
        )
    }

    /** Ordered references for a multi-asset slot, newest position last. */
    suspend fun studioAssets(gameId: Long, kind: ArtworkKind): List<StudioArtworkSlot> =
        artworkRecordDao.findAll(gameId, kind.name).map {
            StudioArtworkSlot(
                sortOrder = it.sortOrder,
                documentUri = it.documentUri,
                provider = it.provider,
                originUrl = it.originUrl,
                providerAssetId = it.providerAssetId,
                sizeBytes = it.sizeBytes,
            )
        }

    /** The position an append would write to — one past the highest in use, or 0 for an empty slot. */
    suspend fun nextSortOrder(gameId: Long, kind: ArtworkKind): Int =
        if (!ArtworkFileNaming.supportsMultiple(kind)) 0
        else (artworkRecordDao.maxSortOrder(gameId, kind.name) + 1)
            .coerceAtMost(ArtworkFileNaming.MAX_SORT_ORDER)

    /** Studio Apply from a browse URL: versioned write, provenance recorded, previous backed up. */
    suspend fun studioApplyFromUrl(
        gameId: Long,
        kind: ArtworkKind,
        url: String,
        provider: String?,
        sortOrder: Int = 0,
        providerAssetId: String? = null,
    ): String? {
        val target = portableTarget(gameId) ?: return internal.saveVersionedFromUrl(gameId, kind, url)
        val (tree, game) = target
        val tmp = ArtworkTempIO.downloadToTemp(httpClient, context.cacheDir, kind, url) ?: return null
        return persistPortable(
            tree, game, kind, tmp, source = SOURCE_USER, userAssigned = true,
            originUrl = url, provider = provider, backupPrevious = true,
            sortOrder = sortOrder, providerAssetId = providerAssetId,
        )
    }

    /**
     * Adds another asset to a multi-asset slot instead of replacing its primary — the write
     * behind "add a screenshot" / "add a video". Single-art kinds fall back to a plain apply, so
     * a caller never has to branch on the kind.
     */
    suspend fun studioAppendFromUrl(
        gameId: Long,
        kind: ArtworkKind,
        url: String,
        provider: String?,
        providerAssetId: String? = null,
    ): String? = studioApplyFromUrl(
        gameId, kind, url, provider,
        sortOrder = nextSortOrder(gameId, kind),
        providerAssetId = providerAssetId,
    )

    /**
     * Removes one position of a multi-asset slot, deleting its file and closing the ordering gap
     * so the remaining assets stay 0..n-1 with the primary at 0.
     */
    suspend fun deleteAssetAt(gameId: Long, kind: ArtworkKind, sortOrder: Int): Boolean {
        val rec = artworkRecordDao.getAt(gameId, kind.name, sortOrder) ?: return false
        val target = portableTarget(gameId)
        if (target != null) {
            runCatching { library.deleteUri(Uri.parse(rec.documentUri)) }
            rec.prevDocumentUri?.let { runCatching { library.deleteUri(Uri.parse(it)) } }
        }
        artworkRecordDao.deleteAtAndCompact(gameId, kind.name, sortOrder)
        return true
    }

    /**
     * Rewrites a slot's order. Only the records move — the files keep their ordinal names, which
     * would otherwise have to be renamed one by one with no way to make that atomic. Relink
     * rebuilds order from those names, so a reorder is re-applied by a later explicit reorder,
     * not silently reverted mid-session.
     */
    suspend fun reorderAssets(gameId: Long, kind: ArtworkKind, orderedSortOrders: List<Int>) {
        val rows = artworkRecordDao.findAll(gameId, kind.name).associateBy { it.sortOrder }
        artworkRecordDao.reorder(gameId, kind.name, orderedSortOrders.mapNotNull { rows[it]?.id })
    }

    /** Studio Apply from a locally-produced file (manual download, cropped bake, local pick copy). */
    suspend fun studioApplyFromFile(
        gameId: Long, kind: ArtworkKind, tempFile: java.io.File, provider: String?, originUrl: String?,
        sortOrder: Int = 0,
    ): String? {
        val target = portableTarget(gameId) ?: return internal.saveFromFile(gameId, kind, tempFile, sortOrder)
        val (tree, game) = target
        return persistPortable(
            tree, game, kind, tempFile, source = SOURCE_USER, userAssigned = true,
            originUrl = originUrl, provider = provider, backupPrevious = true, sortOrder = sortOrder,
        )
    }

    /** Brings the one backed-up previous version back, swapping it with the current (toggle-able). */
    suspend fun restorePrevious(gameId: Long, kind: ArtworkKind, sortOrder: Int = 0): String? {
        val (tree, game) = portableTarget(gameId) ?: return null
        val rec = artworkRecordDao.getAt(gameId, kind.name, sortOrder) ?: return null
        val prevUri = rec.prevDocumentUri?.let { Uri.parse(it) } ?: return null
        if (!internal.isValidRef(rec.prevDocumentUri)) return null
        val curUri = Uri.parse(rec.documentUri)

        val prevTemp = library.copyUriToTemp(prevUri, context.cacheDir, extSuffix(rec.prevRelativePath)) ?: return null
        val curTemp = if (internal.isValidRef(rec.documentUri))
            library.copyUriToTemp(curUri, context.cacheDir, extSuffix(rec.relativePath)) else null

        // Previous → current slot (validated write into the media dir).
        val saved = library.saveFromFile(tree, game.platformId, kind, rec.portableName, prevTemp) ?: run {
            curTemp?.delete(); return null
        }
        // Current → previous slot, so a second press toggles back.
        val newPrev = curTemp?.let {
            val prevExt = extSuffix(rec.relativePath).removePrefix(".")
            library.saveTempIntoPath(
                tree, ArtworkPathResolver.versionsDirSegments(game.platformId, kind),
                "${rec.portableName}.$prevExt", mimeForExt(prevExt), it, deleteTemp = true,
            )
        }
        artworkRecordDao.upsert(
            rec.copy(
                relativePath = ArtworkPathResolver.relativePath(game.platformId, kind, saved.fileName),
                documentUri = saved.uriString,
                sizeBytes = saved.sizeBytes,
                prevDocumentUri = newPrev?.uriString,
                prevRelativePath = newPrev?.let { versionsRelativePath(game.platformId, kind, it.fileName) },
                prevSizeBytes = curTemp?.length() ?: 0L,
                // The restored file is shown as-is; its own original/crop are not tracked.
                cropRect = null,
                hasOriginal = false,
                updatedAt = System.currentTimeMillis(),
            )
        )
        bustCoil(saved.uriString)
        return saved.uriString
    }

    /** Re-download the scraped default from the recorded provenance URL (backs up the current). */
    suspend fun resetToScrapedDefault(gameId: Long, kind: ArtworkKind, sortOrder: Int = 0): String? {
        val rec = artworkRecordDao.getAt(gameId, kind.name, sortOrder) ?: return null
        val url = rec.originUrl ?: return null
        val (tree, game) = portableTarget(gameId) ?: return null
        val tmp = ArtworkTempIO.downloadToTemp(httpClient, context.cacheDir, kind, url) ?: return null
        // source=scrape, unpinned: a reset returns the slot to scraper control.
        return persistPortable(
            tree, game, kind, tmp, source = SOURCE_SCRAPE, userAssigned = false,
            originUrl = url, provider = rec.provider, backupPrevious = true, sortOrder = sortOrder,
            providerAssetId = rec.providerAssetId,
        )
    }

    /** Deletes the current file, its backup and original, and the record. Returns true if anything went. */
    suspend fun clearArtwork(gameId: Long, kind: ArtworkKind): Boolean {
        val target = portableTarget(gameId)
        if (target == null) {
            internal.deleteKind(gameId, kind)
            return true
        }
        val (tree, game) = target
        // Every position of the slot goes: clearing "the screenshot" when a game has five of
        // them must not leave four orphaned files behind.
        val records = artworkRecordDao.findAll(gameId, kind.name)
        for (rec in records) {
            runCatching { library.deleteUri(Uri.parse(rec.documentUri)) }
            rec.prevDocumentUri?.let { runCatching { library.deleteUri(Uri.parse(it)) } }
            library.findInPath(tree, ArtworkPathResolver.originalsDirSegments(game.platformId, kind), rec.portableName)
                ?.let { library.deleteUri(it.uri) }
            artworkRecordDao.deleteById(rec.id)
        }
        internal.deleteKind(gameId, kind)
        return records.isNotEmpty()
    }

    /** The untouched original for re-cropping — the stashed pre-crop copy, or the current file
     *  if nothing has been cropped yet. Caller owns and deletes the returned temp. */
    suspend fun originalToTemp(gameId: Long, kind: ArtworkKind, sortOrder: Int = 0): java.io.File? {
        val (tree, game) = portableTarget(gameId) ?: return null
        val rec = artworkRecordDao.getAt(gameId, kind.name, sortOrder) ?: return null
        val src = if (rec.hasOriginal) {
            library.findInPath(tree, ArtworkPathResolver.originalsDirSegments(game.platformId, kind), rec.portableName)?.uri
                ?: Uri.parse(rec.documentUri)
        } else {
            Uri.parse(rec.documentUri)
        }
        return library.copyUriToTemp(src, context.cacheDir, extSuffix(rec.relativePath))
    }

    /**
     * Persists a baked crop as the current file: stashes the untouched original on first crop,
     * backs up the pre-crop current as the previous version, and records the normalized rect.
     * [bakedTempFile] is consumed.
     */
    suspend fun saveCropBaked(
        gameId: Long,
        kind: ArtworkKind,
        bakedTempFile: java.io.File,
        cropRect: String,
        sortOrder: Int = 0,
    ): String? {
        val target = portableTarget(gameId) ?: run { bakedTempFile.delete(); return null }
        val (tree, game) = target
        val rec = artworkRecordDao.getAt(gameId, kind.name, sortOrder)
        // Stash the pre-crop current as the untouched original — only the FIRST time, so repeated
        // re-crops always frame from the true original rather than a previously-cropped file.
        if (rec != null && !rec.hasOriginal && internal.isValidRef(rec.documentUri)) {
            val origExt = extSuffix(rec.relativePath).removePrefix(".")
            library.copyUriToTemp(Uri.parse(rec.documentUri), context.cacheDir, ".$origExt")?.let { origTmp ->
                library.saveTempIntoPath(
                    tree, ArtworkPathResolver.originalsDirSegments(game.platformId, kind),
                    "${rec.portableName}.$origExt", mimeForExt(origExt), origTmp, deleteTemp = true,
                )
            }
        }
        return persistPortable(
            tree, game, kind, bakedTempFile, source = rec?.source ?: SOURCE_USER,
            userAssigned = rec?.userAssigned ?: true,
            originUrl = rec?.originUrl, provider = rec?.provider, backupPrevious = true,
            cropRect = cropRect, hasOriginal = true, sortOrder = sortOrder,
            providerAssetId = rec?.providerAssetId,
        )
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
        originUrl: String? = null,
        provider: String? = null,
        backupPrevious: Boolean = false,
        cropRect: String? = null,
        hasOriginal: Boolean = false,
        sortOrder: Int = 0,
        providerAssetId: String? = null,
    ): String? {
        val existing = artworkRecordDao.getAt(game.id, kind.name, sortOrder)
        val romFileName = game.romPath?.replace('\\', '/')?.substringAfterLast('/')
        // Keep the established portable name for this slot; only compute a fresh one for a new slot.
        // For multi-asset kinds the stored name carries the ordinal, so the base is recovered from
        // whichever position already exists and every position shares one base + collision suffix.
        val multi = ArtworkFileNaming.supportsMultiple(kind)
        val establishedBase = if (multi) {
            (existing ?: artworkRecordDao.get(game.id, kind.name))
                ?.portableName?.let { ArtworkFileNaming.stripOrdinal(it) }
        } else {
            existing?.portableName
        }
        var portableName = establishedBase
            ?: romFileName?.let { PortableNameResolver.fromRomFileName(it) }
            ?: PortableNameResolver.fromTitle(game.userTitleOverride ?: game.scrapedTitle ?: game.title)
        if (establishedBase == null &&
            artworkRecordDao.findNameCollisions(game.platformId, kind.name, portableName, game.id).isNotEmpty()) {
            portableName = "$portableName (2)"
        }
        // Position 0 keeps the bare name, so single-art kinds and existing installs are untouched.
        if (multi) portableName = ArtworkFileNaming.withOrdinal(portableName, sortOrder)

        // Back up the current file (before saveFromFile deletes the same-stem occupant) so a single
        // "Restore Previous" is possible. Only user/reset/crop writes back up; scrapes never do.
        var prevDocumentUri: String? = existing?.prevDocumentUri
        var prevRelativePath: String? = existing?.prevRelativePath
        var prevSizeBytes: Long = existing?.prevSizeBytes ?: 0L
        if (backupPrevious && existing != null && internal.isValidRef(existing.documentUri)) {
            val prevExt = extSuffix(existing.relativePath).removePrefix(".")
            library.copyUriToTemp(Uri.parse(existing.documentUri), context.cacheDir, ".$prevExt")?.let { backupTmp ->
                val stored = library.saveTempIntoPath(
                    tree, ArtworkPathResolver.versionsDirSegments(game.platformId, kind),
                    "$portableName.$prevExt", mimeForExt(prevExt), backupTmp, deleteTemp = true,
                )
                if (stored != null) {
                    prevDocumentUri = stored.uriString
                    prevRelativePath = versionsRelativePath(game.platformId, kind, stored.fileName)
                    prevSizeBytes = existing.sizeBytes
                }
            }
        }

        val saved = library.saveFromFile(tree, game.platformId, kind, portableName, tempFile) ?: return null
        artworkRecordDao.upsert(
            ArtworkRecordEntity(
                id = existing?.id ?: 0,
                gameId = game.id,
                platformId = game.platformId,
                artworkType = kind.name,
                sortOrder = sortOrder,
                portableName = portableName,
                relativePath = ArtworkPathResolver.relativePath(game.platformId, kind, saved.fileName),
                documentUri = saved.uriString,
                source = source,
                sizeBytes = saved.sizeBytes,
                userAssigned = userAssigned,
                locked = userAssigned,
                originUrl = originUrl ?: existing?.originUrl,
                provider = provider ?: existing?.provider,
                providerAssetId = providerAssetId ?: existing?.providerAssetId,
                prevDocumentUri = prevDocumentUri,
                prevRelativePath = prevRelativePath,
                prevSizeBytes = prevSizeBytes,
                cropRect = cropRect,
                hasOriginal = hasOriginal,
                cropProfileKey = existing?.cropProfileKey,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        )
        bustCoil(saved.uriString)
        return saved.uriString
    }

    // Stable names → stable URIs: bust Coil so the replacement is visible immediately.
    private fun bustCoil(uriString: String) = imageCache.evict(uriString)

    // ".png" from a relative path/uri; ".jpg" fallback so a temp always has a plausible suffix.
    private fun extSuffix(pathOrNull: String?): String {
        val ext = pathOrNull?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() && it.length <= 5 }
        return ".${(ext ?: "jpg").lowercase()}"
    }

    private fun versionsRelativePath(platformId: String, kind: ArtworkKind, fileName: String): String =
        "${ArtworkPathResolver.versionsDirSegments(platformId, kind).joinToString("/")}/$fileName"

    private fun mimeForExt(ext: String): String = when (ext.lowercase()) {
        "png"  -> "image/png"
        "webp" -> "image/webp"
        "pdf"  -> "application/pdf"
        "mp4"  -> "video/mp4"
        "webm" -> "video/webm"
        else   -> "image/jpeg"
    }

    private companion object {
        const val SOURCE_SCRAPE = "scrape"
        const val SOURCE_USER = "user"
    }
}

/** The Studio's read model for one artwork slot (file-info panel + which actions are available). */
data class StudioArtworkInfo(
    val provider: String?,
    val originUrl: String?,
    val relativePath: String?,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    val source: String,
    val userAssigned: Boolean,
    val hasPrevious: Boolean,
    val hasOriginal: Boolean,
    val cropRect: String?,
    val updatedAt: Long,
    val sortOrder: Int = 0,
)

/** One asset of a multi-asset slot, as the Studio's media strip lists them. */
data class StudioArtworkSlot(
    val sortOrder: Int,
    val documentUri: String,
    val provider: String?,
    val originUrl: String?,
    val providerAssetId: String?,
    val sizeBytes: Long,
)
