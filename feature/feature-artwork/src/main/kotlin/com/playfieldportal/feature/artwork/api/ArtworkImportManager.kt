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

    /**
     * Starts an ES-DE-compatible export into [destTreeUri] (a user-picked folder, e.g. an
     * ES-DE install's `downloaded_media`). Copy-only and incremental; the grant is persisted
     * so the worker survives process death.
     */
    fun startExport(destTreeUri: Uri): UUID {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                destTreeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Timber.w(it, "Could not persist export destination grant") }
        return com.playfieldportal.feature.artwork.export.ArtworkExportWorker.enqueue(context, destTreeUri)
    }

    data class RelinkResult(
        val entriesScanned: Int,
        val gamesLinked: Int,
        val orphanEntries: Int,        // files matching no game
        val missingFiles: Int = 0,     // records whose file is gone — record removed, columns cleared
        val changedFiles: Int = 0,     // size drift — record refreshed
        val duplicateNames: Int = 0,   // same portable name twice in one media dir (advisory)
    )

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
     * Scan & relink: walks the v2 library ({platform}/{mediaDir}/) and reconciles it with the
     * database in one pass —
     *  • files are reconnected to games (filenames are ROM stems = matcher pass 1); columns are
     *    written only where the current reference is missing or dead, and user-assigned/locked
     *    records are respected;
     *  • records whose file no longer exists are removed and their game columns cleared (only
     *    runs with a live grant — a *disconnected* folder never destroys state, see §17);
     *  • size drift refreshes the record; duplicate portable names are counted as an advisory.
     * The folder is the source of truth throughout; this never deletes or moves any file.
     */
    suspend fun relinkLibrary(): RelinkResult? = withContext(Dispatchers.IO) {
        val tree = linkedTree() ?: return@withContext null
        if (!folderRepository.hasLiveGrant()) return@withContext null
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
        // One snapshot of all records: provenance preservation, locked lookups, missing sweep.
        val priorRecords = artworkRecordDao.getAll().associateBy { it.gameId to it.artworkType }
        fun lockedTypes(gameId: Long): Set<String> = priorRecords.values
            .filter { it.gameId == gameId && (it.locked || it.userAssigned) }
            .map { it.artworkType }.toSet()
        val upsertedKeys = HashSet<Pair<Long, String>>()

        var scanned = 0
        var linkedGames = 0
        var orphans = 0
        var changedFiles = 0
        var duplicateNames = 0
        val linkedIds = mutableSetOf<Long>()
        for (platformDir in library.listChildren(tree, rootDocId).filter { it.isDirectory }) {
            if (platformDir.name.equals(ArtworkLibraryManifest.DIR_IMPORT, ignoreCase = true)) continue
            val platformId = platformDir.name
            if (byPlatform[platformId].isNullOrEmpty()) continue
            for (mediaDir in library.listChildren(tree, platformDir.documentId).filter { it.isDirectory }) {
                val kind = ArtworkPathResolver.kindForMediaDir(mediaDir.name) ?: continue
                val records = mutableListOf<ArtworkRecordEntity>()
                val stemsInDir = HashSet<String>()
                for (file in library.listChildren(tree, mediaDir.documentId)) {
                    if (file.isDirectory || (file.sizeBytes ?: 0L) <= 0L) continue
                    scanned++
                    if (!stemsInDir.add(ArtworkNaming.fileStem(file.name).lowercase())) duplicateNames++
                    val match = indexFor(platformId).match(file.name)
                    val ids = (match as? ArtworkImportMatcher.Result.Matched)?.gameIds
                    if (ids.isNullOrEmpty()) { orphans++; continue }
                    for (gameId in ids) {
                        val game = games.firstOrNull { it.id == gameId } ?: continue
                        val uri = file.uri.toString()
                        val size = file.sizeBytes ?: 0L
                        // Column-backed kinds: fill when missing or dead; locked slots untouched.
                        val isColumnKind = kind == ArtworkKind.ICON || kind == ArtworkKind.HERO ||
                            kind == ArtworkKind.BACKGROUND || kind == ArtworkKind.LOGO
                        if (isColumnKind && kind.name !in lockedTypes(gameId)) {
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
                        // Refresh the record but PRESERVE provenance — a scan must never launder
                        // a user-assigned/locked asset into a plain "relink" row.
                        val prior = priorRecords[gameId to kind.name]
                        if (prior != null && prior.sizeBytes != size) changedFiles++
                        upsertedKeys.add(gameId to kind.name)
                        records += ArtworkRecordEntity(
                            gameId = gameId,
                            platformId = platformId,
                            artworkType = kind.name,
                            portableName = ArtworkNaming.fileStem(file.name),
                            relativePath = ArtworkPathResolver.relativePath(platformId, kind, file.name),
                            documentUri = uri,
                            source = prior?.source ?: "relink",
                            sizeBytes = size,
                            userAssigned = prior?.userAssigned ?: false,
                            locked = prior?.locked ?: false,
                            createdAt = prior?.createdAt ?: System.currentTimeMillis(),
                        )
                    }
                }
                if (records.isNotEmpty()) artworkRecordDao.upsert(records)
            }
        }

        // Missing sweep: records whose file was not seen by this walk point at nothing — remove
        // them and clear any game column still carrying the dead reference. Only reached with a
        // live grant, so a disconnected folder can never trigger this.
        var missingFiles = 0
        for (prior in priorRecords.values) {
            if ((prior.gameId to prior.artworkType) in upsertedKeys) continue
            missingFiles++
            artworkRecordDao.deleteById(prior.id)
            val game = games.firstOrNull { it.id == prior.gameId } ?: continue
            when (prior.artworkType) {
                ArtworkKind.ICON.name -> if (game.iconUri == prior.documentUri) gameDao.updateIconUri(game.id, null)
                ArtworkKind.HERO.name -> if (game.heroUri == prior.documentUri) gameDao.updateHero(game.id, null)
                ArtworkKind.BACKGROUND.name -> if (game.artworkUri == prior.documentUri) gameDao.updateArtwork(game.id, null)
                ArtworkKind.LOGO.name -> if (game.logoUri == prior.documentUri) gameDao.updateLogo(game.id, null)
            }
        }

        linkedGames = linkedIds.size
        Timber.i(
            "Scan: $scanned files, $linkedGames linked, $orphans unmatched, " +
                "$missingFiles missing, $changedFiles changed, $duplicateNames duplicate names",
        )
        RelinkResult(scanned, linkedGames, orphans, missingFiles, changedFiles, duplicateNames)
    }

    private suspend fun linkedTree(): Uri? =
        folderRepository.getTreeUri()?.let { Uri.parse(it) }

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: ""
}
