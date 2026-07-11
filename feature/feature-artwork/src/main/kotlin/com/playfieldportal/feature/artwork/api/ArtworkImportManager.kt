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
import com.playfieldportal.core.data.saf.SafChild
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

// SGDB grids are ≈2.14 wide; the widest real box fronts (US SNES/N64) are ≈1.37.
private const val GRID_ASPECT_THRESHOLD = 1.6f

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
    private val internalStore: com.playfieldportal.feature.artwork.store.InternalArtworkStore,
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

    // ── Internal-storage migration (M-F2) ─────────────────────────────────────

    /** (files, bytes) of artwork still in internal storage — 0 means nothing to migrate. */
    suspend fun internalArtworkFootprint(): Pair<Int, Long> = internalStore.footprint()

    /** Starts moving internal artwork into the linked folder (worker; survives leaving the screen). */
    fun startInternalMigration(): UUID =
        com.playfieldportal.feature.artwork.migrate.InternalArtworkMigrationWorker.enqueue(context)

    fun cancelInternalMigration() =
        com.playfieldportal.feature.artwork.migrate.InternalArtworkMigrationWorker.cancel(context)

    data class RelinkResult(
        val entriesScanned: Int,
        val gamesLinked: Int,
        val orphanEntries: Int,        // files matching no game
        val missingFiles: Int = 0,     // records whose file is gone — record removed, columns cleared
        val changedFiles: Int = 0,     // size drift — record refreshed
        val duplicateNames: Int = 0,   // same portable name twice in one media dir (advisory)
    )

    /**
     * One-shot in-place layout upgrades, oldest first: v1 (games/{platform}/{slug}/) assets move
     * into the media-dir layout, then any v2 root-level platform dirs move under Artwork/, then
     * true 144:80 icons still sitting in covers/ move to pfp/icon0/ (covers/ belongs to BOX_ART
     * since the icon-display-modes split) — all same-tree moves, no bytes copied — then a relink
     * repoints records and game columns. Idempotent: an up-to-date library is a no-op.
     * Returns how many items were relocated.
     */
    suspend fun migrateV1IfNeeded(): Int = withContext(Dispatchers.IO) {
        val tree = linkedTree() ?: return@withContext 0
        val v1Assets = library.migrateV1Library(tree).assets.size
        val v2Dirs = library.migrateRootPlatformsToArtwork(tree)
        val icon0Moves = relocateIcon0Assets(tree)
        val manifest = library.readManifest(tree)
        if (manifest != null && manifest.formatVersion < ArtworkLibraryManifest.FORMAT_VERSION) {
            library.writeManifest(tree, manifest.copy(formatVersion = ArtworkLibraryManifest.FORMAT_VERSION))
        }
        val relocated = v1Assets + v2Dirs + icon0Moves
        if (relocated > 0) {
            library.clearDirCache()
            relinkLibrary()
            Timber.i(
                "Library layout upgraded: $v1Assets v1 assets + $v2Dirs platform dirs + " +
                    "$icon0Moves icons relocated",
            )
        }
        relocated
    }

    /**
     * Moves ICON assets written before the covers/BOX_ART split out of covers/ into pfp/icon0/,
     * and RECOVERS grids a pre-split scan mislabeled: such a scan matched the SGDB grids still
     * sitting in covers/ as "box art" (and its missing sweep dropped the ICON records), leaving
     * box_art_uri pointing at 144:80 grids. Grids are unmistakably landscape (≈2.1); real box
     * fronts never are (US SNES tops out ≈1.37) — a bounds decode splits them cleanly.
     * Idempotent: relocated/reclaimed records no longer match either filter.
     */
    private suspend fun relocateIcon0Assets(tree: Uri): Int {
        var moved = 0

        // Pass 1 — ICON records still pointing into covers/ (written before the split).
        val stale = artworkRecordDao.getAll().filter {
            it.artworkType == ArtworkKind.ICON.name &&
                !it.relativePath.contains("/${ArtworkPathResolver.DIR_ICON0}/")
        }
        for (record in stale) {
            val fileName = record.relativePath.substringAfterLast('/')
            if (fileName.isBlank()) continue   // pre-v26 row without a path — relink will rebuild it
            val saved = library.relocateAsset(
                tree, record.platformId,
                fromKind = ArtworkKind.BOX_ART,   // covers/ — ICON's old home
                toKind = ArtworkKind.ICON,
                fileName = fileName,
            ) ?: continue
            val oldUri = record.documentUri
            artworkRecordDao.upsert(
                record.copy(
                    relativePath = ArtworkPathResolver.relativePath(record.platformId, ArtworkKind.ICON, saved.fileName),
                    documentUri = saved.uriString,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            gameDao.getById(record.gameId)?.let { game ->
                if (game.iconUri == oldUri || !artworkStore.isValidRef(game.iconUri)) {
                    gameDao.updateIconUri(record.gameId, saved.uriString)
                }
            }
            moved++
        }

        // Pass 2 — BOX_ART records whose file is grid-shaped: reclaim as the game's icon.
        val gridRecords = artworkRecordDao.getAll().filter {
            it.artworkType == ArtworkKind.BOX_ART.name && isGridShaped(it.documentUri)
        }
        for (record in gridRecords) {
            val game = gameDao.getById(record.gameId) ?: continue
            if (game.boxArtUri == record.documentUri) gameDao.updateBoxArt(record.gameId, null)
            val fileName = record.relativePath.substringAfterLast('/')
            val hasIconRecord = artworkRecordDao.get(record.gameId, ArtworkKind.ICON.name) != null
            if (!hasIconRecord && fileName.isNotBlank()) {
                val saved = library.relocateAsset(
                    tree, record.platformId,
                    fromKind = ArtworkKind.BOX_ART,
                    toKind = ArtworkKind.ICON,
                    fileName = fileName,
                )
                if (saved != null) {
                    artworkRecordDao.deleteById(record.id)
                    artworkRecordDao.upsert(
                        record.copy(
                            id = 0,
                            artworkType = ArtworkKind.ICON.name,
                            relativePath = ArtworkPathResolver.relativePath(record.platformId, ArtworkKind.ICON, saved.fileName),
                            documentUri = saved.uriString,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                    gameDao.updateIconUri(record.gameId, saved.uriString)
                    moved++
                }
            } else {
                // The game already has a real icon — the grid record is just mislabeled; drop
                // it (the file stays put; the scan's grid guard won't re-record it).
                artworkRecordDao.deleteById(record.id)
            }
        }
        return moved
    }

    // Bounds-only decode; true when the image is decisively landscape (SGDB grid shape).
    private fun isGridShaped(uriString: String): Boolean = runCatching {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(Uri.parse(uriString))?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        }
        opts.outWidth > 0 && opts.outHeight > 0 &&
            opts.outWidth.toFloat() / opts.outHeight >= GRID_ASPECT_THRESHOLD
    }.getOrDefault(false)

    /**
     * Scan & relink: walks the v2 library ({platform}/{mediaDir}/) and reconciles it with the
     * database in one pass —
     *  • files are reconnected to games: an existing record's portable name is an exact claim
     *    (covers scrape/pick-written files whose sanitized-title or collision-suffixed names
     *    would defeat fuzzy matching), then the import matcher handles foreign files; columns
     *    are written where the current reference is missing, dead, or a remote URL (a library
     *    file always outranks an http ref), and user-assigned/locked records are respected;
     *  • records whose file no longer exists are removed and their game columns cleared (only
     *    runs with a live grant — a *disconnected* folder never destroys state, see §17);
     *  • size drift refreshes the record; duplicate portable names are counted as an advisory.
     * The folder is the source of truth throughout; this never deletes or moves any file.
     */
    suspend fun relinkLibrary(): RelinkResult? = withContext(Dispatchers.IO) {
        val tree = linkedTree() ?: return@withContext null
        if (!folderRepository.hasLiveGrant()) return@withContext null
        // Icons must be out of covers/ BEFORE the walk: covers/ maps to BOX_ART now, so a
        // stale grid left behind would be linked as box art and the missing sweep would drop
        // its ICON record. Idempotent and cheap when there's nothing to move.
        relocateIcon0Assets(tree)
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
        // Records-first matching: anything PFP itself wrote (scrapes, picks, imports) has a
        // record naming its owner, so those files reconnect deterministically — sanitized-title
        // names and collision suffixes ("Name (2)") never have to survive the fuzzy matcher.
        val ownersByName = HashMap<Triple<String, String, String>, MutableSet<Long>>()
        priorRecords.values.forEach { r ->
            ownersByName.getOrPut(Triple(r.platformId, r.artworkType, r.portableName.lowercase())) { mutableSetOf() }
                .add(r.gameId)
        }
        val upsertedKeys = HashSet<Pair<Long, String>>()

        var scanned = 0
        var linkedGames = 0
        var orphans = 0
        var changedFiles = 0
        var duplicateNames = 0
        val linkedIds = mutableSetOf<Long>()
        // Artwork/{platform} children plus any legacy root-level platform dirs (v2 layout).
        for (platformDir in library.platformDirs(tree)) {
            val platformId = platformDir.name
            if (byPlatform[platformId].isNullOrEmpty()) continue
            // Direct media dirs plus the nested PFP namespace (pfp/icon0 → ICON).
            val mediaDirs = mutableListOf<Pair<ArtworkKind, SafChild>>()
            for (child in library.listChildren(tree, platformDir.documentId).filter { it.isDirectory }) {
                if (child.name.equals(ArtworkPathResolver.DIR_PFP, ignoreCase = true)) {
                    library.listChildren(tree, child.documentId)
                        .filter { it.isDirectory }
                        .forEach { sub ->
                            ArtworkPathResolver.kindForMediaDir("${ArtworkPathResolver.DIR_PFP}/${sub.name}")
                                ?.let { mediaDirs += it to sub }
                        }
                } else {
                    ArtworkPathResolver.kindForMediaDir(child.name)?.let { mediaDirs += it to child }
                }
            }
            for ((kind, mediaDir) in mediaDirs) {
                val records = mutableListOf<ArtworkRecordEntity>()
                val stemsInDir = HashSet<String>()
                for (file in library.listChildren(tree, mediaDir.documentId)) {
                    if (file.isDirectory || (file.sizeBytes ?: 0L) <= 0L) continue
                    scanned++
                    // Grid guard: a decisively landscape file in covers/ is an SGDB 144:80
                    // grid (pre-split leftover or user drop), never box art — leave it for
                    // the icon0 recovery pass instead of linking it as BOX_ART.
                    if (kind == ArtworkKind.BOX_ART && isGridShaped(file.uri.toString())) {
                        orphans++
                        continue
                    }
                    val stemLower = ArtworkNaming.fileStem(file.name).lowercase()
                    if (!stemsInDir.add(stemLower)) duplicateNames++
                    // Own records first (exact portable-name hit), fuzzy matcher for foreign files.
                    val ids = ownersByName[Triple(platformId, kind.name, stemLower)]?.toList()
                        ?: (indexFor(platformId).match(file.name) as? ArtworkImportMatcher.Result.Matched)?.gameIds
                    if (ids.isNullOrEmpty()) { orphans++; continue }
                    for (gameId in ids) {
                        val game = games.firstOrNull { it.id == gameId } ?: continue
                        val uri = file.uri.toString()
                        val size = file.sizeBytes ?: 0L
                        // Column-backed kinds: fill when missing or dead; locked slots untouched.
                        val isColumnKind = kind == ArtworkKind.ICON || kind == ArtworkKind.HERO ||
                            kind == ArtworkKind.BACKGROUND || kind == ArtworkKind.LOGO ||
                            kind == ArtworkKind.BOX_ART || kind == ArtworkKind.PHYSICAL_MEDIA ||
                            kind == ArtworkKind.BOX_3D
                        if (isColumnKind && kind.name !in lockedTypes(gameId)) {
                            val current = when (kind) {
                                ArtworkKind.ICON -> game.iconUri
                                ArtworkKind.HERO -> game.heroUri
                                ArtworkKind.BACKGROUND -> game.artworkUri
                                ArtworkKind.BOX_ART -> game.boxArtUri
                                ArtworkKind.PHYSICAL_MEDIA -> game.physicalMediaUri
                                ArtworkKind.BOX_3D -> game.box3dUri
                                else -> game.logoUri
                            }
                            // A library file outranks a remote URL: http refs pass isValidRef
                            // forever (never checked against the network), so a rotted CDN link
                            // would otherwise block the repoint and the game shows no art.
                            val replaceable = !artworkStore.isValidRef(current) ||
                                current?.startsWith("http", ignoreCase = true) == true
                            if (replaceable) {
                                when (kind) {
                                    ArtworkKind.ICON -> gameDao.updateIconUri(gameId, uri)
                                    ArtworkKind.HERO -> gameDao.updateHero(gameId, uri)
                                    ArtworkKind.BACKGROUND -> gameDao.updateArtwork(gameId, uri)
                                    ArtworkKind.BOX_ART -> gameDao.updateBoxArt(gameId, uri)
                                    ArtworkKind.PHYSICAL_MEDIA -> gameDao.updatePhysicalMedia(gameId, uri)
                                    ArtworkKind.BOX_3D -> gameDao.updateBox3d(gameId, uri)
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
                ArtworkKind.BOX_ART.name -> if (game.boxArtUri == prior.documentUri) gameDao.updateBoxArt(game.id, null)
                ArtworkKind.PHYSICAL_MEDIA.name -> if (game.physicalMediaUri == prior.documentUri) gameDao.updatePhysicalMedia(game.id, null)
                ArtworkKind.BOX_3D.name -> if (game.box3dUri == prior.documentUri) gameDao.updateBox3d(game.id, null)
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
