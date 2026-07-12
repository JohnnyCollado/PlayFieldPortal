package com.playfieldportal.feature.artwork.importer

import android.net.Uri
import android.provider.DocumentsContract
import com.playfieldportal.core.data.database.dao.ArtworkImportReportDao
import com.playfieldportal.core.data.database.dao.ArtworkRecordDao
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.ArtworkImportReportEntity
import com.playfieldportal.core.data.database.entity.ArtworkRecordEntity
import com.playfieldportal.core.data.saf.SafChild
import com.playfieldportal.feature.artwork.portable.ArtworkEntryMetadata
import com.playfieldportal.feature.artwork.portable.ArtworkPathResolver
import com.playfieldportal.feature.artwork.portable.PortableArtworkLibrary
import com.playfieldportal.feature.artwork.portable.PortableNameResolver
import com.playfieldportal.feature.artwork.store.ArtworkKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes an approved [ImportPlan]: brings each planned file into `games/{platform}/{slug}/`,
 * writes per-entry provenance (`metadata.json`), updates the game rows and the artwork index.
 *
 * Discipline:
 *  • Resumable — an entry file that already exists (from an interrupted run) is reused, not
 *    re-copied; re-running a plan converges instead of duplicating work.
 *  • One child-listing cursor, one metadata write, one batched index upsert per game.
 *  • Bounded concurrency (SAF providers largely serialize; more workers just queue on binder).
 *  • Locked assets (user-pinned in metadata.json) are never overwritten.
 *  • Per-game error isolation — one unreadable file never aborts the run.
 *  • Cooperative cancellation; a partial run still persists an honest report.
 */
@Singleton
class ArtworkImportExecutor @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val library: PortableArtworkLibrary,
    private val gameDao: GameDao,
    private val artworkRecordDao: ArtworkRecordDao,
    private val reportDao: ArtworkImportReportDao,
    private val videoSnapTranscoder: com.playfieldportal.feature.artwork.video.VideoSnapTranscoder,
) {
    data class Progress(val done: Int, val total: Int, val label: String)

    suspend fun execute(
        plan: ImportPlan,
        transfer: PortableArtworkLibrary.Transfer,
        onProgress: (Progress) -> Unit = {},
    ): ImportSummary = withContext(Dispatchers.IO) {
        val treeUri = Uri.parse(plan.treeUri)
        val startedAt = System.currentTimeMillis()
        val total = plan.itemCount
        val done = AtomicInteger(0)
        val imported = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val bytes = AtomicLong(0)
        val kindCounts = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        var cancelled = false

        // Text metadata first — cheap DB writes, and titles/details are correct in the XMB
        // while the (much slower) file transfers run. Fill-missing-only by construction.
        var metadataApplied = 0
        for (update in plan.metadataUpdates) {
            runCatching {
                gameDao.updateMetadataIfMissing(
                    id = update.gameId,
                    description = update.description,
                    developer = update.developer,
                    publisher = update.publisher,
                    releaseYear = update.releaseYear,
                    genre = update.genre,
                    scrapedTitle = update.name,
                )
                metadataApplied++
            }.onFailure { Timber.w(it, "Metadata update failed for game ${update.gameId}") }
        }

        // Media-dir listings shared across all games in this run — one cursor per directory,
        // used for resume detection ("is this asset already there?").
        val dirListings = java.util.concurrent.ConcurrentHashMap<String, Map<String, SafChild>>()

        try {
            coroutineScope {
                val gate = Semaphore(MAX_CONCURRENT_GAMES)
                plan.games.map { game ->
                    async {
                        gate.withPermit {
                            ensureActive()
                            runCatching {
                                importGame(treeUri, plan, game, transfer, dirListings) { item, outcome ->
                                    onProgress(Progress(done.incrementAndGet(), total, "${game.title} — ${item.kind.lowercase(Locale.ROOT)}"))
                                    when (outcome) {
                                        ItemOutcome.IMPORTED -> {
                                            imported.incrementAndGet()
                                            bytes.addAndGet(item.sizeBytes)
                                            kindCounts.getOrPut(item.kind) { AtomicInteger() }.incrementAndGet()
                                        }
                                        ItemOutcome.SKIPPED -> skipped.incrementAndGet()
                                        ItemOutcome.FAILED -> failed.incrementAndGet()
                                    }
                                }
                            }.onFailure { e ->
                                if (e is CancellationException) throw e
                                failed.addAndGet(game.items.size)
                                done.addAndGet(game.items.size)
                                if (errors.size < ImportSummary.MAX_ERRORS) {
                                    errors += "${game.title}: ${e.message ?: e.javaClass.simpleName}"
                                }
                                Timber.w(e, "Import failed for '%s'", game.title)
                            }
                        }
                    }
                }.awaitAll()
            }
        } catch (e: CancellationException) {
            cancelled = true
        }

        val summary = ImportSummary(
            sourceLabel = plan.sourceLabel,
            transfer = transfer.name,
            imported = imported.get(),
            skipped = skipped.get(),
            failed = failed.get(),
            ambiguous = plan.ambiguous.size,
            unmatched = plan.unmatchedCount,
            bytesWritten = bytes.get(),
            metadataApplied = metadataApplied,
            countsByKind = kindCounts.mapValues { it.value.get() },
            unknownSystemFolders = plan.unknownSystemFolders,
            errors = errors.take(ImportSummary.MAX_ERRORS),
            cancelled = cancelled,
        )
        // The report must persist even for a cancelled run — write it outside the cancelled scope.
        withContext(kotlinx.coroutines.NonCancellable) {
            runCatching {
                reportDao.insert(
                    ArtworkImportReportEntity(
                        source = plan.sourceLabel,
                        startedAt = startedAt,
                        durationMs = System.currentTimeMillis() - startedAt,
                        summaryJson = ImportSummary.encode(summary),
                    )
                )
            }.onFailure { Timber.e(it, "Could not persist import report") }
        }
        Timber.i("Import finished: %s", summary)
        if (cancelled) throw CancellationException("Import cancelled")
        summary
    }

    // ── Per-game work ─────────────────────────────────────────────────────────

    private enum class ItemOutcome { IMPORTED, SKIPPED, FAILED }

    private suspend fun importGame(
        treeUri: Uri,
        plan: ImportPlan,
        game: PlannedGame,
        transfer: PortableArtworkLibrary.Transfer,
        dirListings: java.util.concurrent.ConcurrentHashMap<String, Map<String, SafChild>>,
        onItem: (PlannedItem, ItemOutcome) -> Unit,
    ) {
        // Provenance guard: user-picked or locked assets are never overwritten by an import.
        val existingRecords = artworkRecordDao.getForGame(game.gameId).associateBy { it.artworkType }
        val basePortableName = game.portableName.ifBlank {
            game.romFileName?.let { PortableNameResolver.fromRomFileName(it) }
                ?: PortableNameResolver.fromTitle(game.title)
        }
        val records = mutableListOf<ArtworkRecordEntity>()

        for (item in game.items) {
            val kind = runCatching { ArtworkKind.valueOf(item.kind) }.getOrNull() ?: continue
            val prior = existingRecords[item.kind]
            if (prior != null && (prior.locked || prior.userAssigned)) {
                onItem(item, ItemOutcome.SKIPPED)
                continue
            }

            // ES-DE videos become TWO assets: the full video is imported untouched as VIDEO
            // (Game Detail media strip) via the normal move/copy path, and a 60 s muted snap is
            // transcoded from a temp copy into ICON1 (XMB icon animation). The source is only
            // moved/deleted by the VIDEO import itself (Move mode); the transcode never touches
            // the original. Locked/user-assigned slots of either kind are preserved.
            if (kind == ArtworkKind.VIDEO) {
                val src = sourceChildFor(treeUri, item)
                // A temp copy lets us transcode ICON1 even after Move relocates the original.
                val raw = runCatching {
                    context.contentResolver.openInputStream(src.uri)?.use {
                        com.playfieldportal.feature.artwork.store.ArtworkTempIO
                            .copyToTemp(it, context.cacheDir, ArtworkKind.VIDEO)
                    }
                }.getOrNull()
                var anyImported = false

                // 1) Full video → VIDEO (untouched).
                val priorVideo = existingRecords["VIDEO"]
                if (priorVideo == null || !(priorVideo.locked || priorVideo.userAssigned)) {
                    var pn = basePortableName
                    if (artworkRecordDao.findNameCollisions(game.platformId, "VIDEO", pn, game.gameId).isNotEmpty()) {
                        pn = "$pn (2)"
                    }
                    val dirId = library.mediaDirDocId(treeUri, game.platformId, ArtworkKind.VIDEO)
                    if (dirId != null) {
                        val listing = dirListings[dirId] ?: library.listChildren(treeUri, dirId)
                            .associateBy { it.name.lowercase(Locale.ROOT) }.also { dirListings[dirId] = it }
                        val already = listing.values.firstOrNull {
                            !it.isDirectory && (it.sizeBytes ?: 0L) > 0L &&
                                it.name.substringBeforeLast('.').equals(pn, ignoreCase = true)
                        }
                        val saved = if (already != null)
                            PortableArtworkLibrary.SavedAsset(ArtworkKind.VIDEO, already.uri.toString(), already.name, already.sizeBytes ?: 0L)
                        else library.saveAsset(treeUri, game.platformId, ArtworkKind.VIDEO, pn, src, transfer, listing)
                        if (saved != null) {
                            records += ArtworkRecordEntity(
                                gameId = game.gameId, platformId = game.platformId, artworkType = "VIDEO",
                                portableName = pn,
                                relativePath = ArtworkPathResolver.relativePath(game.platformId, ArtworkKind.VIDEO, saved.fileName),
                                documentUri = saved.uriString, source = sourceTag(plan.sourceId), sizeBytes = saved.sizeBytes,
                            )
                            anyImported = true
                        }
                    }
                }

                // 2) ICON1 snap from the temp copy.
                val priorIcon1 = existingRecords["ICON1"]
                if (raw != null && (priorIcon1 == null || !(priorIcon1.locked || priorIcon1.userAssigned))) {
                    val snap = java.io.File.createTempFile("snap_", ".mp4", context.cacheDir)
                    val ok = runCatching { videoSnapTranscoder.transcode(raw, snap) }
                        .onFailure { Timber.w(it, "Import snap transcode crashed") }.getOrDefault(false)
                    if (ok) {
                        val savedSnap = library.saveFromFile(treeUri, game.platformId, ArtworkKind.ICON1, basePortableName, snap)
                        if (savedSnap != null) {
                            records += ArtworkRecordEntity(
                                gameId = game.gameId, platformId = game.platformId, artworkType = "ICON1",
                                portableName = basePortableName,
                                relativePath = ArtworkPathResolver.relativePath(game.platformId, ArtworkKind.ICON1, savedSnap.fileName),
                                documentUri = savedSnap.uriString, source = sourceTag(plan.sourceId), sizeBytes = savedSnap.sizeBytes,
                            )
                            anyImported = true
                        }
                    } else snap.delete()
                }
                raw?.delete()
                onItem(item, if (anyImported) ItemOutcome.IMPORTED else ItemOutcome.SKIPPED)
                continue
            }

            // Case-insensitive cross-game collision (FAT volumes): keep tags, then suffix.
            var portableName = basePortableName
            if (artworkRecordDao.findNameCollisions(game.platformId, item.kind, portableName, game.gameId).isNotEmpty()) {
                portableName = "$portableName (2)"
            }

            val dirId = library.mediaDirDocId(treeUri, game.platformId, kind)
                ?: error("Could not create ${game.platformId}/${ArtworkPathResolver.mediaDirFor(kind)}")
            val listing = dirListings[dirId] ?: library.listChildren(treeUri, dirId)
                .associateBy { it.name.lowercase(Locale.ROOT) }
                .also { dirListings[dirId] = it }

            // Resume path: an asset already stored under this portable name is reused as-is.
            val already = listing.values.firstOrNull {
                !it.isDirectory && (it.sizeBytes ?: 0L) > 0L &&
                    it.name.substringBeforeLast('.').equals(portableName, ignoreCase = true)
            }
            val saved = if (already != null) {
                PortableArtworkLibrary.SavedAsset(kind, already.uri.toString(), already.name, already.sizeBytes ?: 0L)
            } else {
                library.saveAsset(
                    treeUri, game.platformId, kind, portableName,
                    sourceChildFor(treeUri, item), transfer, listing,
                )
            }

            if (saved == null) {
                onItem(item, ItemOutcome.FAILED)
                continue
            }

            records += ArtworkRecordEntity(
                gameId = game.gameId,
                platformId = game.platformId,
                artworkType = item.kind,
                portableName = portableName,
                relativePath = ArtworkPathResolver.relativePath(game.platformId, kind, saved.fileName),
                documentUri = saved.uriString,
                source = sourceTag(plan.sourceId),
                sizeBytes = saved.sizeBytes,
            )
            updateGameColumn(game.gameId, kind, saved.uriString)
            onItem(item, if (already != null) ItemOutcome.SKIPPED else ItemOutcome.IMPORTED)
        }

        if (records.isNotEmpty()) artworkRecordDao.upsert(records)
        gameDao.mintArtworkKey(game.gameId, game.artworkKey)
    }

    // Candidates carry document ids; rebuild the tree-scoped uri (grant-bounded by construction).
    private fun sourceChildFor(treeUri: Uri, item: PlannedItem): SafChild = SafChild(
        documentId = item.documentId,
        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, item.documentId),
        name = item.displayName,
        mime = null,
        isDirectory = false,
        lastModified = null,
        sizeBytes = item.sizeBytes,
    )

    private suspend fun updateGameColumn(gameId: Long, kind: ArtworkKind, uri: String) = when (kind) {
        ArtworkKind.ICON -> gameDao.updateIconUri(gameId, uri)
        ArtworkKind.HERO -> gameDao.updateHero(gameId, uri)
        ArtworkKind.BACKGROUND -> gameDao.updateArtwork(gameId, uri)
        ArtworkKind.LOGO -> gameDao.updateLogo(gameId, uri)
        ArtworkKind.BOX_ART -> gameDao.updateBoxArt(gameId, uri)
        ArtworkKind.PHYSICAL_MEDIA -> gameDao.updatePhysicalMedia(gameId, uri)
        ArtworkKind.BOX_3D -> gameDao.updateBox3d(gameId, uri)
        else -> Unit    // record-only kinds (manuals, videos, screenshots, titlescreens)
    }

    private fun sourceTag(sourceId: String): String = when (sourceId) {
        "esde" -> ArtworkEntryMetadata.SOURCE_IMPORT_ESDE
        else -> "import-$sourceId"
    }

    companion object {
        private const val MAX_CONCURRENT_GAMES = 3
    }
}
