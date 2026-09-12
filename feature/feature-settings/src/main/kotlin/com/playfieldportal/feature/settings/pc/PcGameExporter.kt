package com.playfieldportal.feature.settings.pc

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.playfieldportal.core.data.database.dao.ArtworkRecordDao
import com.playfieldportal.core.data.database.entity.ArtworkRecordEntity
import com.playfieldportal.core.data.model.IntentUriExtras
import com.playfieldportal.core.data.repository.WindowsLibrarySetup
import com.playfieldportal.core.data.repository.WindowsSetupState
import com.playfieldportal.core.data.saf.SafChild
import com.playfieldportal.core.data.saf.querySafChildren
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.artwork.portable.PortableNameResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** One `.pfpgame` file to write: its name in the import folder, and its content. */
data class PcGameExportFile(
    val fileName: String,
    val export: PcGameExport,
)

/** The outcome of an export, with a ready-made message for settings or a game's menu. */
data class PcGameExportReport(
    val written: Int,
    val skipped: Int,
    val failed: Int,
    val message: String,
)

/**
 * Turns games into `.pfpgame` files (C18 tasks X.3 and X.7). Pure, so file naming, content and
 * ownership are testable without storage.
 */
object PcGameExportBuilder {

    /**
     * One file per game, in [games]' order. A file is named after the game's display title, with
     * `" (2)"`, `" (3)"`, … when two games share a name (compared ignoring case, as FAT does). A game
     * [exportFor] cannot export gets no file.
     */
    fun build(games: List<Game>, artworkByGame: Map<Long, List<ArtworkRecordEntity>>): List<PcGameExportFile> {
        val usedNames = HashSet<String>()
        return games.mapNotNull { game ->
            val export = exportFor(game, artworkByGame[game.id].orEmpty()) ?: return@mapNotNull null
            val base = PortableNameResolver.fromTitle(game.displayTitle)
            var name = base
            var suffix = 2
            while (!usedNames.add(name.lowercase())) name = "$base (${suffix++})"
            PcGameExportFile("$name.${PcGameExportCodec.EXTENSION}", export)
        }
    }

    /**
     * [game]'s entry with its [artwork], or null when the import would reject it: no launcher package,
     * or neither a launch intent nor a shortcut.
     */
    fun exportFor(game: Game, artwork: List<ArtworkRecordEntity>): PcGameExport? {
        val launcherPackage = game.packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val isPin = game.shortcutId != null
        if (!isPin && game.launchIntentUri == null) return null
        return PcGameExport(
            title = game.title,
            scrapedTitle = game.scrapedTitle,
            userTitleOverride = game.userTitleOverride,
            launcherPackage = launcherPackage,
            // A pin is matched on import, never launched from the file.
            launchIntentUri = if (isPin) null else game.launchIntentUri,
            shortcutId = game.shortcutId,
            storefront = game.storefront,
            storefrontGameId = game.storefrontGameId,
            ssId = game.ssId,
            tgdbId = game.tgdbId,
            igdbId = game.igdbId,
            steamGridDbId = game.steamGridDbId,
            artwork = artwork
                .sortedWith(compareBy({ it.artworkType }, { it.sortOrder }))
                // The codec refuses more than this; no real game comes near it.
                .take(PcGameExportCodec.MAX_ARTWORK_ITEMS)
                .map { PcGameExportArtwork(kind = it.artworkType, sortOrder = it.sortOrder, portableName = it.portableName) },
        )
    }

    /**
     * Whether [existing] is [game]'s own export file, so exporting the game again may overwrite it.
     *
     * The same launcher package, and then the same pin, or the same launch intent. After an import
     * the game stores its *sanitized* intent, whose text can differ from the file's (launch flags are
     * stripped), so two intents also count as the same when their typed extras are equal and
     * non-empty: `localGameId`, `app_id`, `shortcut_path` and the like are what name the game.
     */
    fun isSameGame(existing: PcGameExport, game: Game): Boolean {
        if (existing.launcherPackage != game.packageName) return false
        val shortcutId = game.shortcutId
        if (shortcutId != null) return existing.shortcutId == shortcutId
        if (existing.shortcutId != null) return false
        val mine = game.launchIntentUri ?: return false
        val theirs = existing.launchIntentUri ?: return false
        if (mine == theirs) return true
        val myExtras = IntentUriExtras.parse(mine)
        return myExtras.isNotEmpty() && myExtras == IntentUriExtras.parse(theirs)
    }

    /**
     * The file name to export [game] under, when the import folder already holds the `.pfpgame` files
     * in [existing] (keyed by lowercased file name; null for one that could not be read).
     *
     * The display title's name is used when it is free or already holds this game's file. A name
     * holding another game's file, or a file that could not be read, is never overwritten: the name
     * moves on to `" (2)"`, `" (3)"`, … instead.
     */
    fun fileNameFor(game: Game, existing: Map<String, PcGameExport?>): String {
        val base = PortableNameResolver.fromTitle(game.displayTitle)
        var name = base
        var suffix = 2
        while (true) {
            val fileName = "$name.${PcGameExportCodec.EXTENSION}"
            val key = fileName.lowercase()
            if (key !in existing) return fileName
            val file = existing[key]
            if (file != null && isSameGame(file, game)) return fileName
            name = "$base (${suffix++})"
        }
    }
}

/**
 * Writes `.pfpgame` files into `<ROM Root>/windows/import`, which the scan reads with the rest of the
 * launcher exports.
 *
 * - [export] (Export Manual Games, C18 task X.3): every Windows game a fresh install could not bring
 *   back on its own, and every pin with artwork.
 * - [exportGame] (Export Game, C18 task X.7): one Windows game the user picked, whatever it is.
 */
@Singleton
class PcGameExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val windowsLibrarySetup: WindowsLibrarySetup,
    private val gameRepository: GameRepository,
    private val artworkRecordDao: ArtworkRecordDao,
    private val pcGameScanner: PcGameScanner,
) {

    suspend fun export(): PcGameExportReport = withContext(Dispatchers.IO) {
        val folder = when (val found = importFolder()) {
            is ImportFolder.Missing -> return@withContext refusal(found.message)
            is ImportFolder.Found -> found
        }

        val games = gameRepository.getByPlatform(WINDOWS_PLATFORM_ID)
        val artwork = games.associate { it.id to artworkRecordDao.getForGame(it.id) }
        val launcherExports = pcGameScanner.launcherExports()
        val selection = ManualGameExportSelector.select(
            windowsGames = games,
            gamesWithArtwork = artwork.filterValues { it.isNotEmpty() }.keys,
            launcherFiles = launcherExports.files,
            reproducedIntentUris = launcherExports.intentUris,
        )
        val files = PcGameExportBuilder.build(selection.exported, artwork)

        val existing = context.contentResolver.querySafChildren(folder.tree, folder.docId)
            .filterNot { it.isDirectory }
            .associateBy { it.name.lowercase() }
        val written = files.count { file ->
            write(folder.tree, folder.docId, existing[file.fileName.lowercase()]?.uri, file)
        }

        val failed = files.size - written
        val skipped = selection.skipped + (selection.exported.size - files.size)
        Timber.i("Manual PC game export — written=$written skipped=$skipped failed=$failed")
        PcGameExportReport(written, skipped, failed, bulkMessage(written, skipped, failed))
    }

    /**
     * Exports the one game [gameId], the user's explicit choice, so it skips the bulk selection: a game
     * from a launcher export file is exported too, and its import then matches it and restores its
     * artwork names. A same-named file is overwritten only when it is this game's own
     * ([PcGameExportBuilder.fileNameFor]).
     */
    suspend fun exportGame(gameId: Long): PcGameExportReport = withContext(Dispatchers.IO) {
        val game = gameRepository.getById(gameId)
            ?: return@withContext refusal("That game is no longer in the library.")
        if (game.platformId != WINDOWS_PLATFORM_ID) return@withContext refusal("Only PC games can be exported.")
        val export = PcGameExportBuilder.exportFor(game, artworkRecordDao.getForGame(game.id))
            ?: return@withContext refusal("${game.displayTitle} has no launcher to bring it back with, so it can't be exported.")
        val folder = when (val found = importFolder()) {
            is ImportFolder.Missing -> return@withContext refusal(found.message)
            is ImportFolder.Found -> found
        }

        val pfpFiles = context.contentResolver.querySafChildren(folder.tree, folder.docId)
            .filter { !it.isDirectory && it.name.endsWith(".${PcGameExportCodec.EXTENSION}", ignoreCase = true) }
        val fileName = PcGameExportBuilder.fileNameFor(game, pfpFiles.associate { it.name.lowercase() to readExport(it) })
        val target = pfpFiles.firstOrNull { it.name.equals(fileName, ignoreCase = true) }?.uri

        val written = write(folder.tree, folder.docId, target, PcGameExportFile(fileName, export))
        Timber.i("PC game export — gameId=$gameId file=$fileName written=$written")
        if (written) {
            PcGameExportReport(1, 0, 0, "Exported ${game.displayTitle} to windows/import as $fileName.")
        } else {
            PcGameExportReport(0, 0, 1, "Couldn't write $fileName to windows/import.")
        }
    }

    private sealed interface ImportFolder {
        data class Found(val tree: Uri, val docId: String) : ImportFolder
        data class Missing(val message: String) : ImportFolder
    }

    /** The first `<ROM Root>/windows/import`, after the same setup self-heal the scan runs. */
    private suspend fun importFolder(): ImportFolder {
        val setup = runCatching { windowsLibrarySetup.ensure() }.getOrNull()
        if (setup is WindowsSetupState.NoRomRoot) {
            return ImportFolder.Missing("Add a ROM Root first — PFP exports PC games into <root>/windows/import.")
        }
        val (treeUri, docId) = windowsLibrarySetup.importFolders().firstOrNull()
            ?: return ImportFolder.Missing("Couldn't open <windows>/import. Relink the ROM Root and try again.")
        return ImportFolder.Found(Uri.parse(treeUri), docId)
    }

    /** An existing `.pfpgame` file's entry, or null when it is too large or does not decode. */
    private fun readExport(file: SafChild): PcGameExport? {
        if ((file.sizeBytes ?: 0L) > PcGameExportCodec.MAX_CHARS) return null
        val text = runCatching {
            context.contentResolver.openInputStream(file.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return null
        return (PcGameExportCodec.decode(text) as? PcGameExportDecode.Valid)?.export
    }

    /**
     * Writes [file] over [existing] when the folder already has one by that name, so a re-export
     * replaces it instead of SAF adding "(1)". Created as octet-stream: a provider appends the
     * canonical extension of a typed mime, which would make `Portal 2.pfpgame.json`.
     */
    private fun write(tree: Uri, parentDocId: String, existing: Uri?, file: PcGameExportFile): Boolean {
        return runCatching {
            val target = existing ?: DocumentsContract.createDocument(
                context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId),
                MIME_BINARY,
                file.fileName,
            ) ?: return false
            val bytes = PcGameExportCodec.encode(file.export).toByteArray(Charsets.UTF_8)
            context.contentResolver.openOutputStream(target, "wt")?.use { it.write(bytes) } != null
        }.getOrElse { e ->
            Timber.w(e, "Could not write ${file.fileName}")
            false
        }
    }

    private fun refusal(message: String) = PcGameExportReport(0, 0, 0, message)

    private fun bulkMessage(written: Int, skipped: Int, failed: Int): String = when {
        written == 0 && failed == 0 ->
            "No PC games need an export file: launcher exports and pins without artwork come back on their own."
        else -> buildString {
            append("Exported $written PC game(s) to windows/import")
            if (skipped > 0) append(" · $skipped need no file")
            if (failed > 0) append(" · $failed couldn't be written")
            append(".")
        }
    }

    private companion object {
        const val WINDOWS_PLATFORM_ID = "windows"
        const val MIME_BINARY = "application/octet-stream"
    }
}
