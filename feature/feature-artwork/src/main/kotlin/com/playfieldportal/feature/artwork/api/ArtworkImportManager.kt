package com.playfieldportal.feature.artwork.api

import android.content.Context
import android.net.Uri
import com.playfieldportal.core.data.database.dao.ArtworkImportReportDao
import com.playfieldportal.core.data.database.dao.ArtworkIndexDao
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.ArtworkImportReportEntity
import com.playfieldportal.core.data.database.entity.ArtworkIndexEntity
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
    private val artworkIndexDao: ArtworkIndexDao,
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
     * Walks `games/` and reconnects every entry to its game: metadata.json supplies the key,
     * platform and ROM filename, so entries relink even when the DB references were wiped
     * (e.g. by a scrape pass) or the entry sits under a misnamed platform folder. Columns are
     * only written where the current reference is missing or dead — user/scraper values with
     * live files are never touched.
     */
    suspend fun relinkLibrary(): RelinkResult? = withContext(Dispatchers.IO) {
        val tree = linkedTree() ?: return@withContext null
        val rootDocId = android.provider.DocumentsContract.getTreeDocumentId(tree)
        val gamesDir = library.listChildren(tree, rootDocId)
            .firstOrNull { it.isDirectory && it.name.equals(ArtworkLibraryManifest.DIR_GAMES, ignoreCase = true) }
            ?: return@withContext RelinkResult(0, 0, 0)

        val games = gameDao.getAll()
        val byKey = games.filter { it.artworkKey != null }.groupBy { it.artworkKey!! }
        val indexes = HashMap<String, ArtworkImportMatcher.PlatformIndex>()
        fun indexFor(platformId: String) = indexes.getOrPut(platformId) {
            ArtworkImportMatcher.PlatformIndex(
                games.filter { it.platformId == platformId }.map { g ->
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

        var scanned = 0
        var linkedGames = 0
        var orphans = 0
        for (platformDir in library.listChildren(tree, gamesDir.documentId).filter { it.isDirectory }) {
            for (entryDir in library.listChildren(tree, platformDir.documentId).filter { it.isDirectory }) {
                scanned++
                val meta = library.readEntryMetadata(tree, entryDir.documentId) ?: run { orphans++; null } ?: continue
                val targets = byKey[meta.key]
                    ?: meta.romFileName?.let { rom ->
                        (indexFor(meta.platformId).match(rom) as? ArtworkImportMatcher.Result.Matched)
                            ?.gameIds?.mapNotNull { id -> games.firstOrNull { it.id == id } }
                    }
                if (targets.isNullOrEmpty()) { orphans++; continue }

                // Entry files by kind: fixed base names, any image extension.
                val filesByKind = mutableMapOf<ArtworkKind, Pair<String, Long>>()   // uri, size
                for (child in library.listChildren(tree, entryDir.documentId)) {
                    if (child.isDirectory || (child.sizeBytes ?: 0L) <= 0L) continue
                    val base = child.name.substringBeforeLast('.').lowercase(Locale.ROOT)
                    val kind = ArtworkKind.entries.firstOrNull {
                        ArtworkFileNaming.baseName(it).lowercase(Locale.ROOT) == base
                    } ?: continue
                    filesByKind.putIfAbsent(kind, child.uri.toString() to (child.sizeBytes ?: 0L))
                }
                if (filesByKind.isEmpty()) continue

                var linkedAny = false
                for (game in targets) {
                    for ((kind, file) in filesByKind) {
                        val (uri, _) = file
                        val current = when (kind) {
                            ArtworkKind.ICON -> game.iconUri
                            ArtworkKind.HERO -> game.heroUri
                            ArtworkKind.BACKGROUND -> game.artworkUri
                            ArtworkKind.LOGO -> game.logoUri
                            else -> continue
                        }
                        if (artworkStore.isValidRef(current)) continue
                        when (kind) {
                            ArtworkKind.ICON -> gameDao.updateIconUri(game.id, uri)
                            ArtworkKind.HERO -> gameDao.updateHero(game.id, uri)
                            ArtworkKind.BACKGROUND -> gameDao.updateArtwork(game.id, uri)
                            ArtworkKind.LOGO -> gameDao.updateLogo(game.id, uri)
                            else -> Unit
                        }
                        linkedAny = true
                    }
                    gameDao.mintArtworkKey(game.id, meta.key)
                }
                if (linkedAny) linkedGames += targets.size
                artworkIndexDao.upsert(
                    filesByKind.map { (kind, file) ->
                        ArtworkIndexEntity(
                            key = meta.key, kind = kind.name, location = "PORTABLE",
                            docUriOrPath = file.first, sizeBytes = file.second,
                        )
                    },
                )
            }
        }
        Timber.i("Relink: $scanned entries, $linkedGames games linked, $orphans orphans")
        RelinkResult(scanned, linkedGames, orphans)
    }

    private suspend fun linkedTree(): Uri? =
        folderRepository.getTreeUri()?.let { Uri.parse(it) }

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: ""
}
