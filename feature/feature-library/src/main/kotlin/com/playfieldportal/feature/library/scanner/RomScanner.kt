package com.playfieldportal.feature.library.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.core.data.saf.SafChild
import com.playfieldportal.core.data.saf.querySafChildren
import com.playfieldportal.core.data.saf.querySafChildrenOrNull
import com.playfieldportal.core.domain.model.Game
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

data class ScanProgress(
    val currentFolder: String,
    val filesScanned: Int,
    val filesFound: Int,
    val totalEstimated: Int,
)

sealed class ScanResult {
    data class Progress(val progress: ScanProgress) : ScanResult()
    data class Complete(
        val newGames: List<Game>,
        val alreadyInLibrary: Int,
        val unmatched: List<UnmatchedRom>,
        val requiresUserAssignment: List<UnmatchedRom>, // .chd/.img — platform unknown
        // Every supported-extension ROM path the walk saw (new AND already-known), so a caller
        // can compute deletions as dbPaths - presentRomPaths without a second directory pass.
        // Null when the walk never ran (error / no extensions) — callers MUST NOT treat that as
        // "everything is gone".
        val presentRomPaths: Set<String>? = null,
    ) : ScanResult()
    data class Error(val message: String) : ScanResult()
}

data class UnmatchedRom(
    val filePath: String,
    val fileName: String,
    val detectedPlatformId: String?,    // null = truly unknown
)

// Outcome of creating the ES-DE folder structure under a picked root.
data class FolderSetupResult(val created: Int, val existing: Int, val total: Int)

// A frontend-export file found in a Windows/PC folder: GameNative's per-store exports
// (.steam/.epic/.gog/.amazon/.pcgame — content is the store app id) or a Winlator .desktop shortcut
// (launched by path, no content id).
data class PcExportFile(
    val title: String,
    val extension: String,   // lowercase, no dot
    val idContent: String?,  // trimmed file contents (the app id); null for .desktop
    val rawPath: String?,    // derived filesystem path (used for a Winlator .desktop shortcut_path)
    val uri: String,
    // A .desktop file's text: its private paths name the Winlator fork that wrote it.
    val desktopText: String? = null,
)

private val PC_EXPORT_EXTENSIONS = setOf("steam", "epic", "gog", "amazon", "pcgame", "desktop", "pfpgame")

// PFP's own `.pfpgame` export (C18) is a few kilobytes of JSON on shared storage, where any app can
// write. One far larger than that is not read at all; the importer then rejects it as unreadable.
private const val PFP_EXPORT_EXTENSION = "pfpgame"
private const val MAX_PFP_EXPORT_BYTES = 256L * 1024

// The GameNative/GameHub store exports (.steam/.epic/.gog/.amazon/.pcgame) hold nothing but a
// trimmed decimal app id: PcGameScanner.buildPcLaunch requires it to parse as a positive Int, and
// StorefrontIdentity.isPlausibleAppId caps a real id at 12 digits. 256 bytes leaves generous room
// for surrounding whitespace/newlines while staying far below anything a provider could misreport.
private const val MAX_LAUNCHER_EXPORT_BYTES = 256L
// A Winlator .desktop is a few KB of settings; anything far bigger is not one.
private const val MAX_DESKTOP_EXPORT_BYTES = 64L * 1024

// A file child collected during the scanTree walk — name, stable raw path (dedupe/display key)
// and the SAF document URI (content access + launch handle).
private data class SafFileChild(
    val name: String,
    val rawPath: String,
    val uri: String,
)

@Singleton
class RomScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val discImageResolver: DiscImageResolver,
    private val arcadeRomsets: ArcadeRomsetCatalog,
    private val discSetBuilder: DiscSetBuilder,
    private val discCompanionSuppressor: DiscCompanionSuppressor,
    private val m3uPlaylistReader: M3uPlaylistReader,
    private val discRegionReader: DiscRegionReader,
) {

    // ── Per-Memory-Card scan ──────────────────────────────────────────────────
    //
    // Scans exactly one directory and assigns every match to a single platform. Only
    // files whose extension is in [extensions] are considered; everything else (including
    // hidden files and unsupported types) is ignored. This is the authoritative scan path
    // for the Memory Card library: a PSP card scans only its PSP folder for PSP ROMs and
    // can never pull in another console's files.
    fun scanDirectory(
        directory: String,
        extensions: List<String>,
        platformId: String,
        recursive: Boolean,
        existingRomPaths: Set<String>,
    ): Flow<ScanResult> = flow {
        Timber.i("Memory Card scan — platform=$platformId dir=$directory exts=$extensions recursive=$recursive")

        val root = File(directory)
        if (!root.exists() || !root.isDirectory) {
            emit(ScanResult.Error("ROM directory not found: $directory"))
            return@flow
        }

        val allowed = extensions.map { it.removePrefix(".").lowercase() }.toSet()
        if (allowed.isEmpty()) {
            emit(ScanResult.Complete(emptyList(), 0, emptyList(), emptyList()))
            return@flow
        }

        // A directory whose own name carries a directory-game extension IS the game (a PS3 .ps3dir
        // JB folder). It is collected here and deliberately never descended into: its contents are
        // that game's parts, not more games. Found at any depth, and regardless of [recursive] when
        // it sits at the top — recursive governs looking INSIDE ordinary folders, and a game is not
        // an ordinary folder.
        val folderGames = mutableListOf<File>()
        val rootPath = root.absolutePath

        // Collect the folder's files once, then suppress disc companions: a .bin listed in a
        // sibling .cue (or a Dreamcast .gdi track file) is part of a disc, never a game row on its
        // own — the same suppression the ROM-root walk gets from DiscImageResolver.
        //
        // `allowed` is passed in so only a sheet this card can scan may suppress: the replacement
        // row for a suppressed companion is the sheet itself, and a card whose extensions omit
        // .cue (the PS2 default) would otherwise drop cue+bin discs out of the library entirely.
        val allFiles: List<File> = if (recursive) {
            root.walkTopDown()
                .onEnter { dir ->
                    val isGame = dir.absolutePath != rootPath &&
                        DirectoryGameExtensions.isGameFolder(dir.name, allowed)
                    if (isGame) folderGames += dir
                    !isGame   // false prunes the walk here
                }
                .filter { it.isFile }
                .filter { !it.name.startsWith(".") }             // ignore hidden files
                .toList()
        } else {
            val children = root.listFiles()?.toList().orEmpty()
            children.filterTo(folderGames) {
                it.isDirectory && DirectoryGameExtensions.isGameFolder(it.name, allowed)
            }
            children.filter { it.isFile && !it.name.startsWith(".") }
        }
        val suppressedPaths = discImageResolver.resolveFiles(allFiles, scannableSheets = allowed).suppressedPaths

        // Directory games join AFTER suppression: companion suppression is a statement about files
        // inside one folder, and a folder game is not one of them.
        val candidates = allFiles
            .filter { it.extension.lowercase() in allowed }      // only supported extensions
            .filterNot { it.absolutePath in suppressedPaths }    // never companion files
            .plus(folderGames)

        val newGames         = mutableListOf<Game>()
        val seenPaths        = HashSet<String>()                 // de-dupe within this scan
        var alreadyInLibrary = 0
        var filesScanned     = 0

        for (file in candidates) {
            filesScanned++
            val path = file.absolutePath

            if (path in existingRomPaths || !seenPaths.add(path)) {
                alreadyInLibrary++
                continue
            }

            emit(ScanResult.Progress(
                ScanProgress(
                    currentFolder  = directory,
                    filesScanned   = filesScanned,
                    filesFound     = newGames.size,
                    totalEstimated = candidates.size,
                )
            ))

            val (title, resolvedPlatform) =
                when (val d = arcadeRomsets.decide(file.nameWithoutExtension, file.extension.lowercase(), platformId)) {
                    is ArcadeRomsetCatalog.Decision.Route -> d.title to d.platformId
                    ArcadeRomsetCatalog.Decision.Skip -> {
                        Timber.d("Skipped non-romset in CPS card: ${file.name}")
                        continue
                    }
                    ArcadeRomsetCatalog.Decision.UseDefault -> cleanRomTitle(file.nameWithoutExtension) to platformId
                }
            newGames.add(
                Game(
                    title      = title,
                    platformId = resolvedPlatform,
                    romPath    = path,
                )
            )
        }

        Timber.i("Memory Card scan complete — platform=$platformId new=${newGames.size} existing=$alreadyInLibrary")
        emit(ScanResult.Complete(
            discSetBuilder.assign(newGames, discRegionReader::read, m3uPlaylistReader::read),
            alreadyInLibrary, emptyList(), emptyList(),
            presentRomPaths = candidates.mapTo(HashSet()) { it.absolutePath },
        ))

    }.flowOn(Dispatchers.IO)

    // ── Per-Memory-Card SAF scan ──────────────────────────────────────────────
    //
    // The SAF counterpart of [scanDirectory]: walks the granted document tree via a single
    // DocumentsContract child query per directory (see [querySafChildren]) — no MANAGE_EXTERNAL_
    // STORAGE, works on SD/USB volumes. Each match carries both its SAF content:// [Game.romUri]
    // (the preferred launch handle) and a derived raw [Game.romPath] (display + {rom_path} fallback
    // for emulators that read files themselves). Dedupe stays on romPath, keeping parity with the
    // raw-path scan. Mirrors [VideoScanner]/[PhotoScanner]: iterative DFS over document IDs,
    // cancellable via [ensureActive], per-file failures logged and skipped.
    fun scanTree(
        treeUri: String,
        extensions: List<String>,
        platformId: String,
        recursive: Boolean,
        existingRomPaths: Set<String>,
        // When set, the DFS starts at this document id instead of the tree's own root document.
        // Used for the single ROM-root model: [treeUri] is the granted root and [startDocId] is a
        // subfolder under it (a descendant of the grant, so no separate permission is needed).
        startDocId: String? = null,
    ): Flow<ScanResult> = flow {
        Timber.i("Memory Card SAF scan — platform=$platformId tree=$treeUri exts=$extensions recursive=$recursive start=${startDocId ?: "(root)"}")

        val tree = runCatching { Uri.parse(treeUri) }.getOrNull()
        if (tree == null) {
            emit(ScanResult.Error("This library's folder link is invalid. Re-add the folder."))
            return@flow
        }
        val allowed = extensions.map { it.removePrefix(".").lowercase() }.toSet()
        if (allowed.isEmpty()) {
            emit(ScanResult.Complete(emptyList(), 0, emptyList(), emptyList()))
            return@flow
        }

        val newGames         = mutableListOf<Game>()
        val seenPaths        = HashSet<String>()
        val presentPaths     = HashSet<String>()
        var alreadyInLibrary = 0
        var filesScanned     = 0

        // Phase 1 — collect every file child (name / raw path / document uri). Directory
        // traversal only: companion suppression (a .cue hiding its .bin rows) must see the whole
        // folder before any game row is created, so files are processed in phase 2.
        val rootDocId = startDocId ?: DocumentsContract.getTreeDocumentId(tree)
        val walk = walkRomTree(rootDocId, recursive, allowed) { context.contentResolver.querySafChildrenOrNull(tree, it) }
        if (!walk.complete) Timber.w("Memory Card SAF scan — some folders could not be listed; missing flags left untouched")
        // Derive the raw path from the document id (pure string math, no file access) so it stays
        // the stable dedupe key and the {rom_path} value.
        fun SafChild.toFileChild() =
            SafFileChild(name, safDocumentIdToRawPath(documentId, uri.authority) ?: uri.toString(), uri.toString())
        val fileChildren = walk.files.map { it.toFileChild() }
        // Directory games (a PS3 .ps3dir JB folder). Kept apart from [fileChildren] so they take no
        // part in companion suppression, which is a statement about files inside a folder.
        val folderGameChildren = walk.folderGames.map { it.toFileChild() }

        // Phase 2 — companion suppression over SAF: a .cue-listed .bin or a Dreamcast .gdi track
        // file is part of a disc, never a game row on its own (same policy as the raw paths, whose
        // counterpart lives in DiscImageResolver). Sheets are read through their document URIs.
        // `allowed` gates which sheets may suppress, so a card that cannot scan .cue keeps its
        // .bin rows instead of losing the disc (see the raw-path scan above).
        val uriByPath = fileChildren.associate { it.rawPath to it.uri }
        val suppressedPaths = discCompanionSuppressor.suppressedFiles(
            fileChildren.map { ScannedDiscFile(it.rawPath, it.name) },
            scannableSheets = allowed,
        ) { file ->
            val uri = uriByPath[file.rawPath] ?: return@suppressedFiles null
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.bufferedReader()?.readLines()
            }.getOrNull()
        }

        // Phase 3 — the actual scan over the collected files, then the directory games.
        for (child in fileChildren + folderGameChildren) {
            coroutineContext.ensureActive()
            if (child.rawPath in suppressedPaths) continue
            val ext = child.name.substringAfterLast('.', "").lowercase()
            if (ext !in allowed) continue

            filesScanned++
            presentPaths.add(child.rawPath)
            if (child.rawPath in existingRomPaths || !seenPaths.add(child.rawPath)) {
                alreadyInLibrary++
                continue
            }

            emit(ScanResult.Progress(
                ScanProgress(
                    currentFolder  = child.name,
                    filesScanned   = filesScanned,
                    filesFound     = newGames.size,
                    totalEstimated = filesScanned,
                )
            ))

            val stem = child.name.substringBeforeLast('.', child.name)
            val (title, resolvedPlatform) =
                when (val d = arcadeRomsets.decide(stem, ext, platformId)) {
                    is ArcadeRomsetCatalog.Decision.Route -> d.title to d.platformId
                    ArcadeRomsetCatalog.Decision.Skip -> {
                        Timber.d("Skipped non-romset in CPS card: ${child.name}")
                        continue
                    }
                    ArcadeRomsetCatalog.Decision.UseDefault -> cleanRomTitle(stem) to platformId
                }
            newGames.add(
                Game(
                    title      = title,
                    platformId = resolvedPlatform,
                    romPath    = child.rawPath,
                    romUri     = child.uri,
                )
            )
        }

        Timber.i("Memory Card SAF scan complete — platform=$platformId new=${newGames.size} existing=$alreadyInLibrary")
        emit(
            ScanResult.Complete(
                discSetBuilder.assign(newGames, discRegionReader::read, m3uPlaylistReader::read),
                alreadyInLibrary, emptyList(), emptyList(),
                // A walk that could not list every folder cannot say what is gone.
                presentRomPaths = presentPaths.takeIf { walk.complete },
            )
        )

    }.flowOn(Dispatchers.IO)

    // Immediate child directory names of a granted tree — the ES-DE system folders directly under
    // the ROM root (e.g. "gba", "snes", "psx"). One ContentResolver query; files are ignored.
    // Drives the single-scan autoload: each name is mapped to a platform by PlatformFolderHintResolver.
    suspend fun listSubfolderNames(treeUri: String): List<String> =
        listSubfolderNamesOrNull(treeUri) ?: emptyList()

    /**
     * [listSubfolderNames] for callers that must tell an unreadable root from an empty one: null
     * when the tree cannot be parsed or the provider cannot list it — a volume still mounting
     * after a reboot answers this way. A scan must not read that as "no games here" (survey L1).
     */
    suspend fun listSubfolderNamesOrNull(treeUri: String): List<String>? = withContext(Dispatchers.IO) {
        val tree = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return@withContext null
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?: return@withContext null
        context.contentResolver.querySafChildrenOrNull(tree, rootDocId)
            ?.filter { it.isDirectory }
            ?.map { it.name }
    }

    // Creates any missing subfolders (by name) directly under a granted tree via SAF — the ES-DE
    // "set up my ROM structure for me" action. Requires a WRITE grant on [treeUri]. Existing
    // folders (case-insensitive) are left untouched, so it's safe to re-run.
    suspend fun createSubfolders(treeUri: String, names: List<String>): FolderSetupResult =
        withContext(Dispatchers.IO) {
            val tree = runCatching { Uri.parse(treeUri) }.getOrNull()
                ?: return@withContext FolderSetupResult(0, 0, names.size)
            val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
                ?: return@withContext FolderSetupResult(0, 0, names.size)
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootDocId)

            val existingDirs = context.contentResolver.querySafChildren(tree, rootDocId)
                .filter { it.isDirectory }
                .map { it.name.lowercase() }
                .toSet()

            var created = 0
            var existing = 0
            for (name in names) {
                if (name.lowercase() in existingDirs) { existing++; continue }
                val ok = runCatching {
                    DocumentsContract.createDocument(
                        context.contentResolver,
                        parentDocUri,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        name,
                    )
                }.getOrNull() != null
                if (ok) created++ else Timber.w("Could not create ROM subfolder '$name'")
            }
            Timber.i("ES-DE folder setup — created=$created existing=$existing total=${names.size}")
            FolderSetupResult(created, existing, names.size)
        }

    // Scans a granted Windows/PC folder (ES-DE "windows" system) for frontend-export files. For the
    // GameNative store exports it reads the tiny file's contents (the app id); for a Winlator
    // .desktop shortcut it derives the raw path. One iterative DFS, mirroring [scanTree].
    suspend fun scanPcFolder(treeUri: String, startDocId: String? = null): List<PcExportFile> =
        withContext(Dispatchers.IO) {
            val tree = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return@withContext emptyList()
            val rootDocId = startDocId ?: runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
                ?: return@withContext emptyList()

            val out = mutableListOf<PcExportFile>()
            val visited = HashSet<String>().apply { add(rootDocId) }
            val stack = ArrayDeque<String>().apply { addLast(rootDocId) }
            while (stack.isNotEmpty()) {
                coroutineContext.ensureActive()
                val dirDocId = stack.removeLast()
                for (child in context.contentResolver.querySafChildren(tree, dirDocId)) {
                    coroutineContext.ensureActive()
                    if (child.isDirectory) {
                        if (visited.add(child.documentId)) stack.addLast(child.documentId)
                        continue
                    }
                    val ext = child.name.substringAfterLast('.', "").lowercase()
                    if (ext !in PC_EXPORT_EXTENSIONS) continue
                    val title = child.name.substringBeforeLast('.', child.name)
                    // sizeBytes is only an early-out (skip opening a stream the provider already told
                    // us is too big); readBoundedText below is the real guard, since a provider can
                    // misreport or omit sizeBytes.
                    val readCapBytes = when (ext) {
                        PFP_EXPORT_EXTENSION -> MAX_PFP_EXPORT_BYTES
                        "desktop" -> null
                        else -> MAX_LAUNCHER_EXPORT_BYTES
                    }
                    val tooLargeHint = readCapBytes != null && (child.sizeBytes ?: 0L) > readCapBytes
                    val idContent = if (readCapBytes == null || tooLargeHint) null else {
                        val text = readBoundedText(child.uri, readCapBytes)
                        if (text == null) {
                            Timber.w("Oversized or unreadable export file, skipping content: ${child.name}")
                        }
                        text?.trim()
                    }
                    val desktopText = if (ext != "desktop") null else {
                        readBoundedText(child.uri, MAX_DESKTOP_EXPORT_BYTES).also { text ->
                            if (text == null) Timber.w("Oversized or unreadable .desktop, routing without it: ${child.name}")
                        }
                    }
                    out.add(
                        PcExportFile(
                            title       = title,
                            extension   = ext,
                            idContent   = idContent,
                            rawPath     = safDocumentIdToRawPath(child.documentId, child.uri.authority),
                            uri         = child.uri.toString(),
                            desktopText = desktopText,
                        )
                    )
                }
            }
            Timber.i("PC folder scan — found ${out.size} export file(s)")
            out
        }

    // Reads [uri]'s content up to [maxBytes], decoding as UTF-8, or null if the stream can't be
    // opened/read or holds more than [maxBytes]. minSdk (29) predates InputStream.readNBytes(int)
    // (API 33), so this bounds the read with a manual loop instead — a stream is never fully
    // buffered before the size is known, whatever the provider reports for sizeBytes.
    private fun readBoundedText(uri: Uri, maxBytes: Long): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val cap = maxBytes.toInt()
            val chunk = ByteArray(minOf(cap, 8192).coerceAtLeast(1))
            val out = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = stream.read(chunk)
                if (read == -1) break
                total += read
                if (total > cap) return@use null
                out.write(chunk, 0, read)
            }
            out.toString(Charsets.UTF_8.name())
        }
    }.getOrNull()

}

// Converts a SAF externalstorage document id ("primary:ROMs/game.iso", "1A2B-3C4D:Games/game.iso")
// to its raw filesystem path. Pure string math — needs no storage access and is safe to derive even
// without MANAGE_EXTERNAL_STORAGE. Returns null for non-volume document ids (kept as a fallback by
// the caller). Mirrors the derivation used when a card's folder is first picked. [authority] is the
// document's provider: only shared storage uses volume ids, so another app's provider never gets an
// invented /storage path (null keeps the shared-storage reading).
fun safDocumentIdToRawPath(documentId: String, authority: String? = null): String? =
    RomRootRepository.rawPathOfDocument(authority, documentId)

/** What [walkRomTree] reached. [complete] is false when any folder's listing could not be read. */
internal class RomTreeWalk(val files: List<SafChild>, val folderGames: List<SafChild>, val complete: Boolean)

/**
 * The directory half of [RomScanner.scanTree]: an iterative DFS from [rootDocId] collecting
 * non-hidden files and directory games (never descended into: their contents are that game's
 * parts). [list] returns null for an unreadable folder; the walk carries on and reports itself
 * incomplete, so the caller never mistakes a folder it could not read for one that is empty.
 */
internal suspend fun walkRomTree(
    rootDocId: String,
    recursive: Boolean,
    allowed: Set<String>,
    list: (String) -> List<SafChild>?,
): RomTreeWalk {
    val files = mutableListOf<SafChild>()
    val folderGames = mutableListOf<SafChild>()
    var complete = true
    val visited = hashSetOf(rootDocId)
    val stack = ArrayDeque<String>().apply { addLast(rootDocId) }
    while (stack.isNotEmpty()) {
        coroutineContext.ensureActive()
        val children = list(stack.removeLast())
        if (children == null) {
            complete = false
            continue
        }
        for (child in children) {
            coroutineContext.ensureActive()
            if (child.isDirectory) {
                // Checked before [recursive], which governs looking inside ordinary folders only.
                if (DirectoryGameExtensions.isGameFolder(child.name, allowed)) folderGames.add(child)
                else if (recursive && visited.add(child.documentId)) stack.addLast(child.documentId)
                continue
            }
            if (!child.name.startsWith(".")) files.add(child)
        }
    }
    return RomTreeWalk(files, folderGames, complete)
}

// ── ROM title cleaning ──────────────────────────────────────────────────────────
//
// Turns a raw ROM filename stem into a clean display title by stripping the noise that
// dump groups add: region tags, revision/version tags, dump-status tags and any other
// bracketed/parenthesised metadata. e.g.
//   "God of War - Ghost of Sparta (USA) (v1.01)"  ->  "God of War: Ghost of Sparta"
fun cleanRomTitle(raw: String): String {
    var title = raw
        .replace(Regex("\\([^)]*\\)"), " ")   // (USA), (v1.01), (Rev 1), (!) …
        .replace(Regex("\\[[^]]*]"), " ")      // [!], [b1], [T+Eng] …
        .replace('_', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    // " - " between two words is almost always a subtitle separator → ": "
    title = title.replace(Regex("\\s-\\s"), ": ")

    return title.trim().trim(':', '-', ' ').ifBlank { raw.trim() }
}
