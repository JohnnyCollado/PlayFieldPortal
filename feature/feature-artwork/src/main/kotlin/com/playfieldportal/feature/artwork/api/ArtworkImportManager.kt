package com.playfieldportal.feature.artwork.api

import android.content.Context
import android.net.Uri
import com.playfieldportal.core.data.database.dao.ArtworkImportReportDao
import com.playfieldportal.core.data.database.dao.ArtworkRecordDao
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.ArtworkImportReportEntity
import com.playfieldportal.core.data.database.entity.ArtworkRecordEntity
import com.playfieldportal.core.data.repository.ArtworkFolderRepository
import com.playfieldportal.core.data.repository.ArtworkStorageMode
import com.playfieldportal.feature.artwork.importer.ArtworkImportMatcher
import com.playfieldportal.feature.artwork.importer.ArtworkImportPlanner
import com.playfieldportal.feature.artwork.importer.ArtworkImportWorker
import com.playfieldportal.feature.artwork.importer.DetectedImportSource
import com.playfieldportal.feature.artwork.importer.ImportPlan
import com.playfieldportal.feature.artwork.importer.ImportSummary
import com.playfieldportal.feature.artwork.portable.ArtworkLibraryManifest
import com.playfieldportal.feature.artwork.portable.ArtworkNaming
import com.playfieldportal.feature.artwork.portable.ArtworkPathResolver
import com.playfieldportal.feature.artwork.portable.PortableArtworkLibrary
import com.playfieldportal.feature.artwork.store.ArtworkFileNaming
import com.playfieldportal.feature.artwork.store.ArtworkKind
import com.playfieldportal.feature.artwork.store.ArtworkStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point settings UIs use for the artwork folder + import flow — ViewModels
 * never touch the planner, worker, SAF layer, or DAOs directly.
 */
@Singleton
class ArtworkImportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val folderRepository: ArtworkFolderRepository,
    private val library: PortableArtworkLibrary,
    private val planner: ArtworkImportPlanner,
    private val reportDao: ArtworkImportReportDao,
    private val gameDao: GameDao,
    private val artworkRecordDao: ArtworkRecordDao,
    private val artworkStore: ArtworkStore,
) {
    data class LinkResult(
        val manifest: ArtworkLibraryManifest,
        // True when the picked folder already held a PFP library (re-link, not a fresh library).
        val existingLibrary: Boolean,
    )

    data class ReportRow(val entity: ArtworkImportReportEntity, val summary: ImportSummary?)

    val folderTreeUri: Flow<String?> get() = folderRepository.treeUri

    val reports: Flow<List<ReportRow>> = reportDao.observeAll().map { rows ->
        rows.map { ReportRow(it, ImportSummary.parse(it.summaryJson)) }
    }

    suspend fun hasLiveGrant(): Boolean = folderRepository.hasLiveGrant()

    /**
     * Links [treeUri] as the artwork folder: persists the grant, reads or creates the library
     * manifest (creating `games/` + `import/`), and records mode + UUID. Null when the tree is
     * unwritable.
     */
    suspend fun linkFolder(treeUri: Uri): LinkResult? {
        folderRepository.persist(treeUri)
        val existing = library.readManifest(treeUri)
        val manifest = existing ?: library.ensureLibrary(treeUri, appVersion()) ?: run {
            Timber.w("Could not initialize artwork library at $treeUri")
            return null
        }
        folderRepository.setTreeUri(treeUri.toString())
        folderRepository.setStorageMode(ArtworkStorageMode.PORTABLE)
        folderRepository.setLibraryUuid(manifest.libraryUuid)
        library.clearDirCache()
        return LinkResult(manifest, existingLibrary = existing != null)
    }

    /** Releases the grant and clears the stored folder. Files on disk are never touched. */
    suspend fun forgetFolder() = folderRepository.forget()

    suspend fun detectSources(): List<DetectedImportSource> {
        val tree = linkedTree() ?: return emptyList()
        return planner.detectSources(tree)
    }

    suspend fun unrecognizedFolders(detected: List<DetectedImportSource>): List<String> {
        val tree = linkedTree() ?: return emptyList()
        return planner.unrecognizedFolders(tree, detected)
    }

    suspend fun buildPlan(detected: DetectedImportSource): ImportPlan? {
        val tree = linkedTree() ?: return null
        return planner.plan(tree, detected)
    }

    fun startImport(plan: ImportPlan, transfer: PortableArtworkLibrary.Transfer): UUID =
        ArtworkImportWorker.enqueue(context, plan, transfer)

    fun cancelImport() {
        ArtworkImportWorker.cancel(context)
    }

    suspend fun assignAmbiguous(plan: ImportPlan, index: Int, gameId: Long): ImportPlan =
        planner.assignAmbiguous(plan, index, gameId)

    fun skipAmbiguous(plan: ImportPlan, index: Int): ImportPlan =
        planner.skipAmbiguous(plan, index)

    suspend fun clearReports() = reportDao.clear()

    data class RelinkResult(val entriesScanned: Int, val gamesLinked: Int, val orphanEntries: Int)

    /**
     * Migrates a v1 library (games/{platform}/{slug}/) to layout v2 in place — same-tree moves,
     * no bytes copied — then relinks so records and game columns point at the new locations.
     * Idempotent: with no games/ folder this is a no-op. Returns assets moved.
     */
    suspend fun migrateV1IfNeeded(): Int = withContext(Dispatchers.IO) {
        val tree = linkedTree() ?: return@withContext 0
        val result = library.migrateV1Library(tree)
        val manifest = library.readManifest(tree)
        if (manifest != null && manifest.formatVersion < ArtworkLibraryManifest.FORMAT_VERSION) {
            library.writeManifest(tree, manifest.copy(formatVersion = ArtworkLibraryManifest.FORMAT_VERSION))
        }
        if (result.assets.isNotEmpty()) {
            library.clearDirCache()
            relinkLibrary()
            Timber.i("Library migrated to layout v2: ${result.assets.size} assets relocated")
        }
        result.assets.size
    }

    /**
     * Walks the v2 library ({platform}/{mediaDir}/) and reconnects every asset to its game —
     * filenames are ROM stems, so this is the matcher's pass 1 by construction. Columns are
     * only written where the current reference is missing or dead; user-assigned/locked
     * records are respected.
     */
    suspend fun relinkLibrary(): RelinkResult? = withContext(Dispatchers.IO) {
        val tree = linkedTree() ?: return@withContext null
        val rootDocId = android.provider.DocumentsContract.getTreeDocumentId(tree)

        val games = gameDao.getAll()
        val byPlatform = games.groupBy { it.platformId }
        val indexes = HashMap<String, ArtworkImportMatcher.PlatformIndex>()
        fun indexFor(platformId: String) = indexes.getOrPut(platformId) {
            ArtworkImportMatcher.PlatformIndex(
                byPlatform[platformId].orEmpty().map { g ->
                    ArtworkImportMatcher.GameRef(
                        id = g.id,
                        romStem = g.romPath?.replace('\\', '/')?.substringAfterLast('/')
                            ?.let { ArtworkNaming.fileStem(it) },
                        displayTitle = g.userTitleOverride ?: g.scrapedTitle ?: g.title,
                        scrapedTitle = g.scrapedTitle,
                    )
                },
            )
        }
        val lockedByGame = HashMap<Long, Set<String>>()
        suspend fun lockedTypes(gameId: Long): Set<String> = lockedByGame.getOrPut(gameId) {
            artworkRecordDao.getForGame(gameId)
                .filter { it.locked || it.userAssigned }.map { it.artworkType }.toSet()
        }

        var scanned = 0
        var linkedGames = 0
        var orphans = 0
        val linkedIds = mutableSetOf<Long>()
        for (platformDir in library.listChildren(tree, rootDocId).filter { it.isDirectory }) {
            if (platformDir.name.equals(ArtworkLibraryManifest.DIR_IMPORT, ignoreCase = true)) continue
            val platformId = platformDir.name
            if (byPlatform[platformId].isNullOrEmpty()) continue
            for (mediaDir in library.listChildren(tree, platformDir.documentId).filter { it.isDirectory }) {
                val kind = ArtworkPathResolver.kindForMediaDir(mediaDir.name) ?: continue
                val records = mutableListOf<ArtworkRecordEntity>()
                for (file in library.listChildren(tree, mediaDir.documentId)) {
                    if (file.isDirectory || (file.sizeBytes ?: 0L) <= 0L) continue
                    scanned++
                    val match = indexFor(platformId).match(file.name)
                    val ids = (match as? ArtworkImportMatcher.Result.Matched)?.gameIds
                    if (ids.isNullOrEmpty()) { orphans++; continue }
                    for (gameId in ids) {
                        if (kind.name in lockedTypes(gameId)) continue
                        val game = games.firstOrNull { it.id == gameId } ?: continue
                        val uri = file.uri.toString()
                        // Column-backed kinds only; fill when missing or the current ref is dead.
                        val isColumnKind = kind == ArtworkKind.ICON || kind == ArtworkKind.HERO ||
                            kind == ArtworkKind.BACKGROUND || kind == ArtworkKind.LOGO
                        if (isColumnKind) {
                            val current = when (kind) {
                                ArtworkKind.ICON -> game.iconUri
                                ArtworkKind.HERO -> game.heroUri
                                ArtworkKind.BACKGROUND -> game.artworkUri
                                else -> game.logoUri
                            }
                            if (!artworkStore.isValidRef(current)) {
                                when (kind) {
                                    ArtworkKind.ICON -> gameDao.updateIconUri(gameId, uri)
                                    ArtworkKind.HERO -> gameDao.updateHero(gameId, uri)
                                    ArtworkKind.BACKGROUND -> gameDao.updateArtwork(gameId, uri)
                                    else -> gameDao.updateLogo(gameId, uri)
                                }
                                linkedIds.add(gameId)
                            }
                        }
                        records += ArtworkRecordEntity(
                            gameId = gameId,
                            platformId = platformId,
                            artworkType = kind.name,
                            portableName = ArtworkNaming.fileStem(file.name),
                            relativePath = ArtworkPathResolver.relativePath(platformId, kind, file.name),
                            documentUri = uri,
                            source = "relink",
                            sizeBytes = file.sizeBytes ?: 0L,
                        )
                    }
                }
                if (records.isNotEmpty()) artworkRecordDao.upsert(records)
            }
        }
        linkedGames = linkedIds.size
        Timber.i("Relink v2: $scanned files, $linkedGames games linked, $orphans unmatched files")
        RelinkResult(scanned, linkedGames, orphans)
    }

    private suspend fun linkedTree(): Uri? =
        folderRepository.getTreeUri()?.let { Uri.parse(it) }

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: ""
}
