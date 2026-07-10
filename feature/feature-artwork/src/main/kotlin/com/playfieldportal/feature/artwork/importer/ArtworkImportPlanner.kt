package com.playfieldportal.feature.artwork.importer

import android.net.Uri
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.feature.artwork.portable.ArtworkKeyFactory
import com.playfieldportal.feature.artwork.portable.ArtworkNaming
import com.playfieldportal.feature.artwork.portable.PortableArtworkLibrary
import com.playfieldportal.feature.artwork.portable.PortableNameResolver
import com.playfieldportal.feature.artwork.store.ArtworkKind
import com.playfieldportal.feature.artwork.store.ArtworkStore
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the import preview: detects launcher folders under `import/`, enumerates their artwork,
 * matches each file to a game, and filters to what actually needs importing. Read-only — nothing
 * is copied, moved, or written until the user approves the plan.
 */
@Singleton
class ArtworkImportPlanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: PortableArtworkLibrary,
    private val gameDao: GameDao,
    private val artworkStore: ArtworkStore,
    private val esDeSource: EsDeImportSource,
) {
    // Extensible registry: future launchers (Daijisho, Pegasus, …) are added here only.
    private val sources: List<ArtworkImportSource> get() = listOf(esDeSource)

    /** Recognized launcher folders under `import/`, tried against every registered source. */
    suspend fun detectSources(treeUri: Uri): List<DetectedImportSource> = withContext(Dispatchers.IO) {
        library.listImportSources(treeUri).mapNotNull { folder ->
            sources.firstNotNullOfOrNull { it.detect(treeUri, folder) }
        }
    }

    /** Names of `import/` children no source recognized (shown as "unrecognized layout"). */
    suspend fun unrecognizedFolders(treeUri: Uri, detected: List<DetectedImportSource>): List<String> =
        withContext(Dispatchers.IO) {
            val known = detected.map { it.folderDocId }.toSet()
            library.listImportSources(treeUri).filter { it.documentId !in known }.map { it.name }
        }

    suspend fun plan(treeUri: Uri, detected: DetectedImportSource): ImportPlan = withContext(Dispatchers.IO) {
        val source = sources.first { it.sourceId == detected.sourceId }
        val enumeration = source.enumerate(treeUri, detected)

        val games = gameDao.getAll()
        val byPlatform = games.groupBy { it.platformId }
        val indexes = HashMap<String, ArtworkImportMatcher.PlatformIndex>()
        val gameById = games.associateBy { it.id }

        val plannedByGame = LinkedHashMap<Long, MutableList<PlannedItem>>()
        val ambiguous = mutableListOf<AmbiguousCandidate>()
        var unmatched = 0
        var skippedExisting = 0

        for (candidate in enumeration.candidates) {
            val index = indexes.getOrPut(candidate.platformId) {
                ArtworkImportMatcher.PlatformIndex(
                    byPlatform[candidate.platformId].orEmpty().map { it.toGameRef() },
                )
            }
            when (val result = index.match(candidate.displayName)) {
                is ArtworkImportMatcher.Result.Matched -> {
                    // Several ids = duplicate rows of one physical game (.cue + .bin) — the
                    // artwork applies to every row so each stays consistent in the XMB.
                    for (gameId in result.gameIds) {
                        val game = gameById.getValue(gameId)
                        when (val need = needFor(game, candidate.kind)) {
                            Need.SATISFIED -> skippedExisting++
                            Need.MISSING, Need.STALE -> {
                                val items = plannedByGame.getOrPut(game.id) { mutableListOf() }
                                val item = PlannedItem(
                                    kind = candidate.kind,
                                    documentId = candidate.documentId,
                                    displayName = candidate.displayName,
                                    sizeBytes = candidate.sizeBytes,
                                    confidence = result.confidence,
                                    replacesStale = need == Need.STALE,
                                )
                                // One file per kind per game — the strongest match wins (an
                                // exact-filename hit replaces an index-prefixed duplicate).
                                val existing = items.firstOrNull { it.kind == candidate.kind }
                                when {
                                    existing == null -> items += item
                                    item.confidence.ordinal < existing.confidence.ordinal -> {
                                        items[items.indexOf(existing)] = item
                                        skippedExisting++
                                    }
                                    else -> skippedExisting++
                                }
                            }
                        }
                    }
                }
                is ArtworkImportMatcher.Result.Ambiguous -> ambiguous += AmbiguousCandidate(
                    candidate = candidate,
                    gameIds = result.gameIds,
                    gameTitles = result.gameIds.mapNotNull { gameById[it]?.displayTitleOrNull() },
                )
                ArtworkImportMatcher.Result.Unmatched -> unmatched++
            }
        }

        // ── gamelist.xml metadata pass ────────────────────────────────────────
        // Entries match through the same per-platform index: <path> filename first (pass 1),
        // then <name> through the title passes. Fill-missing-only, applied by the executor.
        val metadataUpdates = mutableListOf<MetadataUpdate>()
        val metadataSeen = mutableSetOf<Long>()
        for ((platformId, gamelistDocId) in detected.gamelistDocIds) {
            val index = indexes.getOrPut(platformId) {
                ArtworkImportMatcher.PlatformIndex(byPlatform[platformId].orEmpty().map { it.toGameRef() })
            }
            val entries = runCatching {
                val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, gamelistDocId)
                context.contentResolver.openInputStream(uri)?.use { EsDeGamelistParser.parse(it) }
            }.onFailure { Timber.w(it, "Could not read gamelist for $platformId") }.getOrNull().orEmpty()
            for (entry in entries) {
                val result = index.match(entry.romFileName).let { first ->
                    if (first is ArtworkImportMatcher.Result.Matched || entry.name.isNullOrBlank()) first
                    else index.match("${entry.name}.xml")
                }
                val ids = (result as? ArtworkImportMatcher.Result.Matched)?.gameIds ?: continue
                for (gameId in ids) {
                    if (!metadataSeen.add(gameId)) continue
                    if (entry.description == null && entry.developer == null && entry.publisher == null &&
                        entry.releaseYear == null && entry.genre == null && entry.name == null
                    ) continue
                    metadataUpdates += MetadataUpdate(
                        gameId = gameId,
                        name = entry.name,
                        description = entry.description,
                        developer = entry.developer,
                        publisher = entry.publisher,
                        releaseYear = entry.releaseYear,
                        genre = entry.genre,
                    )
                }
            }
        }

        val plannedGames = plannedByGame.map { (gameId, items) ->
            val game = gameById.getValue(gameId)
            val key = game.artworkKey ?: ArtworkKeyFactory.keyFor(gameEntityToDomainLite(game))
                ?: "rom/${game.platformId}/${ArtworkNaming.slug(game.title)}"
            val romFileName = game.romPath?.replace('\\', '/')?.substringAfterLast('/')
            PlannedGame(
                gameId = gameId,
                platformId = game.platformId,
                artworkKey = key,
                portableName = romFileName?.let { PortableNameResolver.fromRomFileName(it) }
                    ?: PortableNameResolver.fromTitle(game.displayTitleOrNull() ?: game.title),
                title = game.displayTitleOrNull() ?: game.title,
                romFileName = romFileName,
                // Kind priority: visible slots first so the XMB fills fastest.
                items = items.sortedBy { KIND_PRIORITY.indexOf(it.kind).let { i -> if (i < 0) 99 else i } },
            )
        }

        ImportPlan(
            sourceId = detected.sourceId,
            sourceLabel = detected.label,
            treeUri = treeUri.toString(),
            games = plannedGames,
            ambiguous = ambiguous,
            unmatchedCount = unmatched,
            skippedExistingCount = skippedExisting,
            unknownSystemFolders = enumeration.unknownSystemFolders,
            platformsWithoutGames = detected.systems
                .filter { byPlatform[it.platformId].isNullOrEmpty() }
                .map { it.folderName }
                .distinct(),
            metadataUpdates = metadataUpdates,
        ).also {
            Timber.i(
                "Import plan '%s': %d games / %d items, %d ambiguous, %d unmatched, %d already satisfied",
                it.sourceLabel, it.games.size, it.itemCount, it.ambiguous.size, it.unmatchedCount,
                it.skippedExistingCount,
            )
        }
    }

    /**
     * Review resolution: moves an ambiguous candidate into the plan as a confirmed item for
     * [gameId]. Returns the updated plan (the ambiguous entry removed either way).
     */
    suspend fun assignAmbiguous(plan: ImportPlan, ambiguousIndex: Int, gameId: Long): ImportPlan =
        withContext(Dispatchers.IO) {
            val entry = plan.ambiguous.getOrNull(ambiguousIndex) ?: return@withContext plan
            val remaining = plan.ambiguous.filterIndexed { i, _ -> i != ambiguousIndex }
            val game = gameDao.getById(gameId) ?: return@withContext plan.copy(ambiguous = remaining)

            val item = PlannedItem(
                kind = entry.candidate.kind,
                documentId = entry.candidate.documentId,
                displayName = entry.candidate.displayName,
                sizeBytes = entry.candidate.sizeBytes,
                confidence = MatchConfidence.SIMPLIFIED_TITLE,
                replacesStale = needFor(game, entry.candidate.kind) == Need.STALE,
            )
            val existing = plan.games.firstOrNull { it.gameId == gameId }
            val games = if (existing != null) {
                if (existing.items.any { it.kind == item.kind }) plan.games   // kind already planned
                else plan.games.map { if (it.gameId == gameId) it.copy(items = it.items + item) else it }
            } else {
                val key = game.artworkKey ?: ArtworkKeyFactory.keyFor(gameEntityToDomainLite(game))
                    ?: "rom/${game.platformId}/${ArtworkNaming.slug(game.title)}"
                val romFileName = game.romPath?.replace('\\', '/')?.substringAfterLast('/')
                plan.games + PlannedGame(
                    gameId = gameId,
                    platformId = game.platformId,
                    artworkKey = key,
                    portableName = romFileName?.let { PortableNameResolver.fromRomFileName(it) }
                        ?: PortableNameResolver.fromTitle(game.displayTitleOrNull() ?: game.title),
                    title = game.displayTitleOrNull() ?: game.title,
                    romFileName = romFileName,
                    items = listOf(item),
                )
            }
            plan.copy(games = games, ambiguous = remaining)
        }

    /** Review resolution: drops an ambiguous candidate without importing it. */
    fun skipAmbiguous(plan: ImportPlan, ambiguousIndex: Int): ImportPlan =
        plan.copy(ambiguous = plan.ambiguous.filterIndexed { i, _ -> i != ambiguousIndex })

    // ── Helpers ───────────────────────────────────────────────────────────────

    private enum class Need { MISSING, STALE, SATISFIED }

    // For column-backed kinds, need is judged from the game row (valid ref = satisfied; dead
    // ref = stale → overwrite). Extra kinds (SCREENSHOT, …) have no column — the executor skips
    // them if the entry file already exists, so they always plan as MISSING here.
    private fun needFor(game: GameEntity, kindName: String): Need {
        val ref = when (kindName) {
            ArtworkKind.ICON.name -> game.iconUri
            ArtworkKind.HERO.name -> game.heroUri
            ArtworkKind.BACKGROUND.name -> game.artworkUri
            ArtworkKind.LOGO.name -> game.logoUri
            else -> return Need.MISSING
        }
        return when {
            ref.isNullOrBlank() -> Need.MISSING
            artworkStore.isValidRef(ref) -> Need.SATISFIED
            else -> Need.STALE
        }
    }

    private fun GameEntity.toGameRef() = ArtworkImportMatcher.GameRef(
        id = id,
        romStem = romPath?.replace('\\', '/')?.substringAfterLast('/')
            ?.let { ArtworkNaming.fileStem(it) },
        displayTitle = displayTitleOrNull() ?: title,
        scrapedTitle = scrapedTitle,
    )

    private fun GameEntity.displayTitleOrNull(): String? =
        userTitleOverride?.takeIf { it.isNotBlank() }
            ?: scrapedTitle?.takeIf { it.isNotBlank() }
            ?: title.takeIf { it.isNotBlank() }

    // ArtworkKeyFactory takes the domain model; build the minimal fields it reads.
    private fun gameEntityToDomainLite(game: GameEntity) =
        com.playfieldportal.core.domain.model.Game(
            id = game.id,
            title = game.title,
            platformId = game.platformId,
            romPath = game.romPath,
            romUri = game.romUri,
            packageName = game.packageName,
            emulatorPackage = game.emulatorPackage,
            artworkUri = game.artworkUri,
            heroUri = game.heroUri,
            logoUri = game.logoUri,
            iconUri = game.iconUri,
            description = game.description,
            developer = game.developer,
            publisher = game.publisher,
            releaseYear = game.releaseYear,
            genre = game.genre,
            steamGridDbId = game.steamGridDbId,
            isManualEntry = game.isManualEntry,
            contentType = com.playfieldportal.core.domain.model.GameContentType.fromName(game.contentType),
            shortcutId = game.launchShortcutId,
        )

    companion object {
        private val KIND_PRIORITY = listOf(
            ArtworkKind.ICON.name, ArtworkKind.HERO.name,
            ArtworkKind.BACKGROUND.name, ArtworkKind.LOGO.name,
        )
    }
}
