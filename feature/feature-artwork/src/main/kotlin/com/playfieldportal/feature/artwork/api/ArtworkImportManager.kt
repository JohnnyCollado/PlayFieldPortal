package com.playfieldportal.feature.artwork.api

import android.content.Context
import android.net.Uri
import com.playfieldportal.core.data.database.dao.ArtworkImportReportDao
import com.playfieldportal.core.data.database.dao.ArtworkOrphanFileDao
import com.playfieldportal.core.data.database.dao.ArtworkRecordDao
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.ArtworkImportReportEntity
import com.playfieldportal.core.data.database.entity.ArtworkOrphanFileEntity
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
import com.playfieldportal.feature.artwork.importer.RelinkOwnerLookup
import com.playfieldportal.feature.artwork.importer.RelinkSlotOrdering
import com.playfieldportal.feature.artwork.portable.ArtworkIdentityIndex
import com.playfieldportal.feature.artwork.portable.ArtworkIdentityRecorder
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// SGDB grids are ≈2.14 wide; the widest real box fronts (US SNES/N64) are ≈1.37.
private const val GRID_ASPECT_THRESHOLD = 1.6f

// artwork_records.source for a file the user tied to a game by hand in the orphan picker.
private const val SOURCE_MANUAL_LINK = "manual_link"

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
    private val identityRecorder: ArtworkIdentityRecorder,
    private val orphanFileDao: ArtworkOrphanFileDao,
) {

    // One relink at a time. A user can now start one from All Games while the automated trigger
    // fires on resume; two walks over one folder would race on artwork_records and on the
    // identity index, and the loser would write rows built from a half-stale snapshot.
    private val relinkMutex = Mutex()

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

    /**
     * How much of the library a relink walks (C22 task T5).
     *
     * This is not a performance knob — it changes what the walk is *allowed to conclude*.
     * [FullLibrary] has seen every file, so it may declare a record's file missing. [Platforms]
     * has seen a subset and may not: see the sweep's guard in [relinkLibrary].
     */
    sealed interface RelinkScope {
        data object FullLibrary : RelinkScope
        data class Platforms(val platformIds: Set<String>) : RelinkScope
    }

    data class RelinkResult(
        val entriesScanned: Int,
        val gamesLinked: Int,
        val orphanEntries: Int,        // files matching no game — retained in artwork_orphan_files
        val missingFiles: Int = 0,     // records whose file is gone — record removed, columns cleared
        val changedFiles: Int = 0,     // size drift — record refreshed
        val duplicateNames: Int = 0,   // same portable name twice in one media dir (advisory)
        // Which walk produced this. A caller must not infer a scoped run from missingFiles == 0 —
        // a full walk over a healthy library reports zero too.
        val scope: RelinkScope = RelinkScope.FullLibrary,
    )

    /** Per-platform progress during a relink: (platforms done, platforms total, current label). */
    fun interface RelinkProgress {
        fun onProgress(done: Int, total: Int, label: String)
    }

    /** One multi-asset slot's file, collected during the walk so its final position can be
     * decided after every file in the (game, kind) slot has been seen (D1, task 1.5). */
    private data class MultiAssetFile(
        val fileStem: String,
        val ordinal: Int,
        val documentUri: String,
        val sizeBytes: Long,
        val relativePath: String,
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
     *
     * [claims] are artwork names a `.pfpgame` export says belong to a game —
     * `(platform, kind, portable name lowercased)` → game id (C18 task X.5). After a fresh install
     * there are no records, so they are what reconnects a manually added game's files exactly
     * instead of through the fuzzy matcher. The lookup order is [RelinkOwnerLookup]'s.
     *
     * [scope] restricts the walk. See [RelinkScope] — a scoped walk deliberately performs no
     * missing sweep and clears no game column.
     *
     * Serialized by [relinkMutex]: the automated trigger and a user-invoked relink can now both
     * fire, and two concurrent walks over one folder would race on the record table and on the
     * identity index.
     */
    suspend fun relinkLibrary(
        claims: Map<Triple<String, String, String>, Long> = emptyMap(),
        identitySeeds: List<ArtworkIdentityIndex.Entry> = emptyList(),
        scope: RelinkScope = RelinkScope.FullLibrary,
        progress: RelinkProgress? = null,
    ): RelinkResult? = withContext(Dispatchers.IO) {
        relinkMutex.withLock {
            relinkLocked(claims, identitySeeds, scope, progress)
        }
    }

    private suspend fun relinkLocked(
        claims: Map<Triple<String, String, String>, Long>,
        identitySeeds: List<ArtworkIdentityIndex.Entry>,
        scope: RelinkScope,
        progress: RelinkProgress?,
    ): RelinkResult? {
        val tree = linkedTree() ?: return null
        if (!folderRepository.hasLiveGrant()) return null
        // Icons must be out of covers/ BEFORE the walk: covers/ maps to BOX_ART now, so a
        // stale grid left behind would be linked as box art and the missing sweep would drop
        // its ICON record. Idempotent and cheap when there's nothing to move.
        relocateIcon0Assets(tree)

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
        // Keyed by POSITION as well as kind — a game with five screenshots has five records, and
        // a (gameId, type) key would collapse them to one and let the missing sweep delete four.
        val priorRecords = artworkRecordDao.getAll()
            .associateBy { Triple(it.gameId, it.artworkType, it.sortOrder) }
        // Multi-asset provenance lookup (D1, task 1.5): keyed by NAME, not position — a delete or
        // reorder renumbers positions without renaming files, so a file must find its own record by
        // the name it actually carries, never by whatever position happens to hold it right now.
        val priorByName = priorRecords.values.associateBy {
            Triple(it.gameId, it.artworkType, it.portableName.lowercase())
        }
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
        // Durable identity (task D.3). The index says which game's ids each file was written for;
        // this maps those ids onto the games that exist NOW, so a file reconnects even when its
        // name — and the ROM's name — has changed since. Built from the same tokensOf as the index
        // itself, because two spellings of one id would simply never meet.
        // Seeds first (task D.4b): a `.pfpgame` states its game's ids, so its artwork gains durable
        // identity on the import that restores it rather than waiting for a later scrape. They are
        // merged in before matching so this very walk can already resolve by them.
        // The recorder is the identity index's only reader and writer (task 1.4 / D2): seeds are
        // buffered through it so they are already reflected by `current(tree)` below, and this
        // walk's own backfill is buffered the same way and flushed once at the end.
        if (identitySeeds.isNotEmpty()) identityRecorder.recordAll(tree, identitySeeds)
        val identityIndex = identityRecorder.current(tree)
        val ownersByToken = HashMap<String, MutableSet<Long>>()
        games.forEach { g ->
            ArtworkIdentityIndex.tokensOf(
                romCrc32 = g.romCrc32, ssId = g.ssId, tgdbId = g.tgdbId,
                igdbId = g.igdbId, sgdbId = g.steamGridDbId, artworkKey = g.artworkKey,
            ).forEach { token -> ownersByToken.getOrPut(token) { mutableSetOf() }.add(g.id) }
        }
        // A prior record counts as seen when ITS RECORD was matched (by name, for a multi-asset
        // slot; by position, for a single-art one) — never when some position merely got upserted,
        // since after a delete/reorder a position can be upserted by a file that belongs to a
        // different record entirely (task 1.5).
        val matchedPriorIds = HashSet<Long>()
        // Backfill (task D.4): every file this walk links gets an identity row built from the game
        // it landed on. This is what gives an EXISTING library durable identity — D.2 only records
        // files written after it shipped, so without this a pre-D.2 folder would stay name-matched
        // forever. Collected during the walk, written once at the end.
        val identityRows = mutableListOf<ArtworkIdentityIndex.Entry>()

        var scanned = 0
        var linkedGames = 0
        var orphans = 0
        var changedFiles = 0
        var duplicateNames = 0
        val linkedIds = mutableSetOf<Long>()
        // Unmatched files, retained rather than merely counted (task T1) — this is what the orphan
        // picker reads. Collected during the walk and written once at the end, scoped the same way
        // the walk was, so a scoped run never speaks for a platform it did not visit.
        val orphanRows = mutableListOf<ArtworkOrphanFileEntity>()
        // Artwork/{platform} children plus any legacy root-level platform dirs (v2 layout).
        val platformDirs = library.platformDirs(tree).let { dirs ->
            when (scope) {
                is RelinkScope.FullLibrary -> dirs
                is RelinkScope.Platforms -> dirs.filter { it.name in scope.platformIds }
            }
        }
        val walkedPlatformIds = mutableSetOf<String>()
        var platformsDone = 0
        for (platformDir in platformDirs) {
            val platformId = platformDir.name
            progress?.onProgress(platformsDone, platformDirs.size, platformId)
            platformsDone++
            if (byPlatform[platformId].isNullOrEmpty()) continue
            walkedPlatformIds += platformId
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
                // Multi-asset files (D1): collected per game while the directory is walked, then
                // ordered and given contiguous positions once every file in the slot has been
                // seen — a file's final position can depend on every other file's prior record,
                // so it cannot be decided file-by-file the way a single-art kind's can.
                val multiAssetByGame = LinkedHashMap<Long, MutableList<MultiAssetFile>>()
                val supersededPriorIds = mutableListOf<Long>()
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
                    val fileStem = ArtworkNaming.fileStem(file.name)
                    val stemLower = fileStem.lowercase()
                    if (!stemsInDir.add(stemLower)) duplicateNames++
                    // Claims, then own records (exact portable-name hits), then the fuzzy matcher for
                    // foreign files. The FULL stem is always tried before an ordinal-stripped base,
                    // so a ROM whose own name ends in "_07" can never be mistaken for another game's
                    // seventh screenshot (C16 task 0.4). The order lives in RelinkOwnerLookup.
                    val multi = ArtworkFileNaming.supportsMultiple(kind)
                    val baseStem = if (multi) ArtworkFileNaming.stripOrdinal(fileStem) else fileStem
                    val ids = RelinkOwnerLookup.owners(
                        platformId = platformId,
                        kind = kind.name,
                        fileName = file.name,
                        fileStem = fileStem,
                        baseStem = baseStem,
                        claims = claims,
                        recordOwners = ownersByName,
                        fuzzyMatch = { name ->
                            (indexFor(platformId).match(name) as? ArtworkImportMatcher.Result.Matched)?.gameIds
                        },
                        // The file's own row, resolved to whichever live game its strongest
                        // surviving id names. An empty list means "the row names nobody any more",
                        // and the name tiers below still get their turn.
                        identityOwners = { stem ->
                            identityIndex.find(platformId, kind.name, stem)?.let { row ->
                                row.tokens()
                                    .firstNotNullOfOrNull { token -> ownersByToken[token] }
                                    ?.toList()
                                    ?: emptyList()
                            }
                        },
                    )
                    if (ids.isNullOrEmpty()) {
                        orphans++
                        orphanRows += ArtworkOrphanFileEntity(
                            platformId = platformId,
                            artworkType = kind.name,
                            fileName = file.name,
                            stem = fileStem,
                            documentUri = file.uri.toString(),
                            sizeBytes = file.sizeBytes ?: 0L,
                        )
                        continue
                    }
                    val uri = file.uri.toString()
                    val size = file.sizeBytes ?: 0L

                    if (multi) {
                        // Position is decided after the whole slot is seen (below) — only the
                        // file's identity is collected here.
                        for (gameId in ids) {
                            val game = games.firstOrNull { it.id == gameId } ?: continue
                            identityRows += ArtworkIdentityIndex.Entry(
                                platformId = platformId,
                                kind = kind.name,
                                portableName = fileStem,
                                romCrc32 = game.romCrc32,
                                ssId = game.ssId,
                                tgdbId = game.tgdbId,
                                igdbId = game.igdbId,
                                sgdbId = game.steamGridDbId,
                                artworkKey = game.artworkKey,
                            )
                            multiAssetByGame.getOrPut(gameId) { mutableListOf() } += MultiAssetFile(
                                fileStem = fileStem,
                                ordinal = ArtworkFileNaming.ordinalOf(fileStem),
                                documentUri = uri,
                                sizeBytes = size,
                                relativePath = ArtworkPathResolver.relativePath(platformId, kind, file.name),
                            )
                        }
                        continue
                    }

                    // Single-art kinds always occupy position 0.
                    val sortOrder = 0
                    for (gameId in ids) {
                        val game = games.firstOrNull { it.id == gameId } ?: continue
                        // Column-backed kinds: fill when missing or dead; locked slots untouched.
                        // Column kinds are all single-art, so this only ever sees position 0 —
                        // the guard states the invariant rather than relying on it.
                        val isColumnKind = sortOrder == 0 && (
                            kind == ArtworkKind.ICON || kind == ArtworkKind.HERO ||
                                kind == ArtworkKind.BACKGROUND || kind == ArtworkKind.LOGO ||
                                kind == ArtworkKind.BOX_ART || kind == ArtworkKind.PHYSICAL_MEDIA ||
                                kind == ArtworkKind.BOX_3D
                            )
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
                        // The scrape reuses the hero file as the full-screen background
                        // (artworkUri = heroPath) whenever a hero exists, so most games have no
                        // fanart/ file of their own — after a wipe there is nothing under
                        // BACKGROUND for the walk to refill artworkUri from. Mirror the scrape's
                        // rule: a HERO file also repoints a missing/dead background column.
                        if (kind == ArtworkKind.HERO && sortOrder == 0 &&
                            ArtworkKind.BACKGROUND.name !in lockedTypes(gameId)
                        ) {
                            val bg = game.artworkUri
                            if (!artworkStore.isValidRef(bg) || bg?.startsWith("http", ignoreCase = true) == true) {
                                gameDao.updateArtwork(gameId, uri)
                                linkedIds.add(gameId)
                            }
                        }
                        // Refresh the record but PRESERVE provenance — a scan must never launder
                        // a user-assigned/locked asset into a plain "relink" row.
                        val prior = priorRecords[Triple(gameId, kind.name, sortOrder)]
                        if (prior != null && prior.sizeBytes != size) changedFiles++
                        prior?.let { matchedPriorIds.add(it.id) }
                        identityRows += ArtworkIdentityIndex.Entry(
                            platformId = platformId,
                            kind = kind.name,
                            portableName = fileStem,
                            romCrc32 = game.romCrc32,
                            ssId = game.ssId,
                            tgdbId = game.tgdbId,
                            igdbId = game.igdbId,
                            sgdbId = game.steamGridDbId,
                            artworkKey = game.artworkKey,
                        )
                        records += ArtworkRecordEntity(
                            gameId = gameId,
                            platformId = platformId,
                            artworkType = kind.name,
                            sortOrder = sortOrder,
                            portableName = fileStem,
                            relativePath = ArtworkPathResolver.relativePath(platformId, kind, file.name),
                            documentUri = uri,
                            source = prior?.source ?: "relink",
                            sizeBytes = size,
                            userAssigned = prior?.userAssigned ?: false,
                            locked = prior?.locked ?: false,
                            originUrl = prior?.originUrl,
                            provider = prior?.provider,
                            providerAssetId = prior?.providerAssetId,
                            cropRect = prior?.cropRect,
                            hasOriginal = prior?.hasOriginal ?: false,
                            cropProfileKey = prior?.cropProfileKey,
                            createdAt = prior?.createdAt ?: System.currentTimeMillis(),
                        )
                    }
                }

                // Multi-asset slots (D1, task 1.5): order each game's linked files — a file with a
                // prior record (matched by NAME) keeps that record's relative order; a file with no
                // prior record sorts after them by filename ordinal — then assign contiguous
                // positions 0..n-1. `upsert`'s REPLACE strategy deletes whatever currently occupies
                // a position before inserting the new row, so writing the whole reordered slot in
                // one call can never collide with the unique (game_id, artwork_type, sort_order)
                // index, even when two files swap positions.
                for ((gameId, files) in multiAssetByGame) {
                    val ordered = RelinkSlotOrdering.order(
                        files = files,
                        stemOf = { it.fileStem },
                        ordinalOf = { it.ordinal },
                        priorSortOrder = { nameLower -> priorByName[Triple(gameId, kind.name, nameLower)]?.sortOrder },
                    )
                    ordered.forEachIndexed { position, f ->
                        val prior = priorByName[Triple(gameId, kind.name, f.fileStem.lowercase())]
                        if (prior != null && prior.sizeBytes != f.sizeBytes) changedFiles++
                        prior?.let { matchedPriorIds.add(it.id) }
                        records += ArtworkRecordEntity(
                            gameId = gameId,
                            platformId = platformId,
                            artworkType = kind.name,
                            sortOrder = position,
                            portableName = f.fileStem,
                            relativePath = f.relativePath,
                            documentUri = f.documentUri,
                            source = prior?.source ?: "relink",
                            sizeBytes = f.sizeBytes,
                            userAssigned = prior?.userAssigned ?: false,
                            locked = prior?.locked ?: false,
                            originUrl = prior?.originUrl,
                            provider = prior?.provider,
                            providerAssetId = prior?.providerAssetId,
                            cropRect = prior?.cropRect,
                            hasOriginal = prior?.hasOriginal ?: false,
                            cropProfileKey = prior?.cropProfileKey,
                            createdAt = prior?.createdAt ?: System.currentTimeMillis(),
                        )
                    }
                    // REPLACE only clears positions 0..n-1. A MATCHED record parked at n or above (the
                    // slot shrank because another of its files went missing) is skipped by the missing
                    // sweep, so it would survive as a duplicate of the row just written for its file.
                    priorRecords.values
                        .filter { it.gameId == gameId && it.artworkType == kind.name && it.sortOrder >= ordered.size }
                        .filter { it.id in matchedPriorIds }
                        .mapTo(supersededPriorIds) { it.id }
                }
                if (records.isNotEmpty()) artworkRecordDao.upsert(records)
                supersededPriorIds.forEach { artworkRecordDao.deleteById(it) }
            }
        }

        // Missing sweep: records whose file was not seen by this walk point at nothing — remove
        // them and clear any game column still carrying the dead reference. Only reached with a
        // live grant, so a disconnected folder can never trigger this.
        //
        // STRUCTURALLY UNREACHABLE FOR A SCOPED WALK (task T5), not merely disabled. A scoped walk
        // has not looked at most of the library, so "not seen by this walk" does not mean "gone".
        // Even restricted to the scoped platforms it is refused: the sweep is the only destructive
        // step in the whole relink, its correctness depends on every listChildren call having
        // succeeded, and the automated trigger fires on app resume and media mount — exactly when
        // a SAF provider is most likely to return a short listing. A transient failure that costs
        // a user-invoked relink one bad run would, on the automated path, delete records silently
        // and repeatedly. The games a scoped walk serves are newly added rows, which have no prior
        // records to sweep anyway.
        var missingFiles = 0
        for (prior in if (scope is RelinkScope.FullLibrary) priorRecords.values else emptyList()) {
            if (prior.id in matchedPriorIds) continue
            missingFiles++
            artworkRecordDao.deleteById(prior.id)
            val game = games.firstOrNull { it.id == prior.gameId } ?: continue
            // Only the primary is ever wired to a game column; a vanished extra screenshot must
            // not clear a column it never owned.
            if (prior.sortOrder != 0) continue
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

        // Buffer the backfilled identity and flush once — the recorder writes only when something
        // actually changed, and refuses to write while the folder's index is unreadable (task 1.3 /
        // 1.4 / D2 / D3), so this walk can never clobber an index it could not read or rebuild from
        // whatever this process could see.
        identityRecorder.recordAll(tree, identityRows)
        identityRecorder.flush(tree)

        // Orphan retention (task T1). A walk's output is the complete truth for the platforms it
        // visited, so the rows are REPLACED over that scope rather than merged — a file that has
        // since been matched or deleted has to disappear from the picker.
        when (scope) {
            is RelinkScope.FullLibrary -> orphanFileDao.replaceAll(orphanRows)
            // The scoped set, not just the platforms that produced rows: a platform walked and
            // found clean must have its old rows cleared, and it contributes none.
            is RelinkScope.Platforms -> orphanFileDao.replaceForPlatforms(walkedPlatformIds, orphanRows)
        }

        linkedGames = linkedIds.size
        Timber.i(
            "Scan (${if (scope is RelinkScope.FullLibrary) "full" else "scoped"}): $scanned files, " +
                "$linkedGames linked, $orphans unmatched, " +
                "$missingFiles missing, $changedFiles changed, $duplicateNames duplicate names",
        )
        progress?.onProgress(platformDirs.size, platformDirs.size, "")
        return RelinkResult(scanned, linkedGames, orphans, missingFiles, changedFiles, duplicateNames, scope)
    }

    // ── Orphan recovery (C22 task T4) ─────────────────────────────────────────

    /** Files the last relink could not place, for the orphan picker. */
    val orphanFiles: Flow<List<ArtworkOrphanFileEntity>> get() = orphanFileDao.observeAll()

    suspend fun orphanCount(): Int = orphanFileDao.count()

    /**
     * Assigns one retained orphan to [gameId] by hand (AD-8).
     *
     * The file is **never renamed or moved** — the folder is the user's, and the record is how PFP
     * remembers the link. `userAssigned` is set so no later scrape or relink overwrites the
     * choice, and an identity row is written so the link survives the ROM being renamed. The next
     * relink then reconnects this file through the records tier without the user doing anything.
     *
     * Returns false when the orphan row or the game has gone since the list was rendered.
     */
    suspend fun assignOrphan(
        platformId: String,
        artworkType: String,
        fileName: String,
        gameId: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        val orphan = orphanFileDao.getAll().firstOrNull {
            it.platformId == platformId && it.artworkType == artworkType && it.fileName == fileName
        } ?: return@withContext false
        val game = gameDao.getAll().firstOrNull { it.id == gameId } ?: return@withContext false
        val kind = runCatching { ArtworkKind.valueOf(artworkType) }.getOrNull() ?: return@withContext false

        // Append for a multi-asset kind, overwrite position 0 for a single-art one — the same rule
        // the walk uses, so a later relink finds this record exactly where it expects it.
        val sortOrder = if (ArtworkFileNaming.supportsMultiple(kind)) {
            artworkRecordDao.maxSortOrder(gameId, kind.name) + 1
        } else {
            0
        }
        val now = System.currentTimeMillis()
        artworkRecordDao.upsert(
            ArtworkRecordEntity(
                gameId = gameId,
                platformId = platformId,
                artworkType = kind.name,
                sortOrder = sortOrder,
                portableName = orphan.stem,
                relativePath = ArtworkPathResolver.relativePath(platformId, kind, orphan.fileName),
                documentUri = orphan.documentUri,
                source = SOURCE_MANUAL_LINK,
                sizeBytes = orphan.sizeBytes,
                userAssigned = true,
                createdAt = now,
                updatedAt = now,
            )
        )
        // Same replaceability rule as the walk: a library file outranks an empty, dead or remote
        // reference, and never displaces one the user already chose.
        if (sortOrder == 0) {
            val current = when (kind) {
                ArtworkKind.ICON -> game.iconUri
                ArtworkKind.HERO -> game.heroUri
                ArtworkKind.BACKGROUND -> game.artworkUri
                ArtworkKind.LOGO -> game.logoUri
                ArtworkKind.BOX_ART -> game.boxArtUri
                ArtworkKind.PHYSICAL_MEDIA -> game.physicalMediaUri
                ArtworkKind.BOX_3D -> game.box3dUri
                else -> null
            }
            val replaceable = !artworkStore.isValidRef(current) ||
                current?.startsWith("http", ignoreCase = true) == true
            if (replaceable) {
                when (kind) {
                    ArtworkKind.ICON -> gameDao.updateIconUri(gameId, orphan.documentUri)
                    ArtworkKind.HERO -> gameDao.updateHero(gameId, orphan.documentUri)
                    ArtworkKind.BACKGROUND -> gameDao.updateArtwork(gameId, orphan.documentUri)
                    ArtworkKind.LOGO -> gameDao.updateLogo(gameId, orphan.documentUri)
                    ArtworkKind.BOX_ART -> gameDao.updateBoxArt(gameId, orphan.documentUri)
                    ArtworkKind.PHYSICAL_MEDIA -> gameDao.updatePhysicalMedia(gameId, orphan.documentUri)
                    ArtworkKind.BOX_3D -> gameDao.updateBox3d(gameId, orphan.documentUri)
                    else -> Unit
                }
            }
        }
        // Durable identity, so a later ROM rename does not orphan this file all over again.
        linkedTree()?.let { tree ->
            identityRecorder.recordAll(
                tree,
                listOf(
                    ArtworkIdentityIndex.Entry(
                        platformId = platformId,
                        kind = kind.name,
                        portableName = orphan.stem,
                        romCrc32 = game.romCrc32,
                        ssId = game.ssId,
                        tgdbId = game.tgdbId,
                        igdbId = game.igdbId,
                        sgdbId = game.steamGridDbId,
                        artworkKey = game.artworkKey,
                    )
                ),
            )
            identityRecorder.flush(tree)
        }
        orphanFileDao.delete(platformId, artworkType, fileName)
        true
    }

    private suspend fun linkedTree(): Uri? =
        folderRepository.getTreeUri()?.let { Uri.parse(it) }

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: ""
}
