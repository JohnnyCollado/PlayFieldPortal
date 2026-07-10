package com.playfieldportal.feature.artwork.importer

import android.net.Uri
import android.provider.DocumentsContract
import com.playfieldportal.core.data.database.dao.ArtworkImportReportDao
import com.playfieldportal.core.data.database.dao.ArtworkIndexDao
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.ArtworkImportReportEntity
import com.playfieldportal.core.data.database.entity.ArtworkIndexEntity
import com.playfieldportal.core.data.saf.SafChild
import com.playfieldportal.feature.artwork.portable.ArtworkEntryMetadata
import com.playfieldportal.feature.artwork.portable.PortableArtworkLibrary
import com.playfieldportal.feature.artwork.store.ArtworkFileNaming
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
    private val library: PortableArtworkLibrary,
    private val gameDao: GameDao,
    private val artworkIndexDao: ArtworkIndexDao,
    private val reportDao: ArtworkImportReportDao,
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

        try {
            coroutineScope {
                val gate = Semaphore(MAX_CONCURRENT_GAMES)
                plan.games.map { game ->
                    async {
                        gate.withPermit {
                            ensureActive()
                            runCatching {
                                importGame(treeUri, plan, game, transfer) { item, outcome ->
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
        onItem: (PlannedItem, ItemOutcome) -> Unit,
    ) {
        val entryDirId = library.entryDirDocId(treeUri, game.platformId, game.slug)
            ?: error("Could not create entry folder games/${game.platformId}/${game.slug}")
        val existing: Map<String, SafChild> =
            library.listChildren(treeUri, entryDirId).associateBy { it.name.lowercase(Locale.ROOT) }

        var metadata = library.readEntryMetadata(treeUri, entryDirId) ?: ArtworkEntryMetadata(
            key = game.artworkKey,
            platformId = game.platformId,
            title = game.title,
            romFileName = game.romFileName,
        )
        val lockedKinds = metadata.assets.filter { it.locked }.map { it.kind }.toSet()

        val indexRows = mutableListOf<ArtworkIndexEntity>()

        for (item in game.items) {
            val kind = runCatching { ArtworkKind.valueOf(item.kind) }.getOrNull() ?: continue
            if (item.kind in lockedKinds) {
                onItem(item, ItemOutcome.SKIPPED)
                continue
            }

            // Resume path: an asset of this kind already in the entry folder is reused as-is.
            val base = ArtworkFileNaming.baseName(kind).lowercase(Locale.ROOT)
            val already = existing.values.firstOrNull {
                !it.isDirectory && it.name.lowercase(Locale.ROOT).substringBeforeLast('.') == base &&
                    (it.sizeBytes ?: 0L) > 0L
            }
            val saved = if (already != null) {
                PortableArtworkLibrary.SavedAsset(kind, already.uri.toString(), already.name, already.sizeBytes ?: 0L)
            } else {
                val sourceChild = sourceChildFor(treeUri, item)
                library.saveIntoEntry(treeUri, entryDirId, kind, sourceChild, transfer, existing)
            }

            if (saved == null) {
                onItem(item, ItemOutcome.FAILED)
                continue
            }

            metadata = metadata.withAsset(
                ArtworkEntryMetadata.AssetRecord(
                    kind = item.kind,
                    fileName = saved.fileName,
                    source = sourceTag(plan.sourceId),
                    originalFileName = item.displayName,
                    sizeBytes = saved.sizeBytes,
                    savedAt = System.currentTimeMillis(),
                )
            )
            indexRows += ArtworkIndexEntity(
                key = game.artworkKey,
                kind = item.kind,
                location = "PORTABLE",
                docUriOrPath = saved.uriString,
                sizeBytes = saved.sizeBytes,
            )
            updateGameColumn(game.gameId, kind, saved.uriString)
            onItem(item, if (already != null) ItemOutcome.SKIPPED else ItemOutcome.IMPORTED)
        }

        library.writeEntryMetadata(treeUri, entryDirId, metadata)
        if (indexRows.isNotEmpty()) artworkIndexDao.upsert(indexRows)
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
        else -> Unit    // extra kinds live in the entry folder + index only
    }

    private fun sourceTag(sourceId: String): String = when (sourceId) {
        "esde" -> ArtworkEntryMetadata.SOURCE_IMPORT_ESDE
        else -> "import-$sourceId"
    }

    companion object {
        private const val MAX_CONCURRENT_GAMES = 3
    }
}
