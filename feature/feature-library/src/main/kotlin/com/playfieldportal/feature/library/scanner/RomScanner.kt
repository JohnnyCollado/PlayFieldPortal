package com.playfieldportal.feature.library.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.playfieldportal.core.domain.model.Game
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
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
    ) : ScanResult()
    data class Error(val message: String) : ScanResult()
}

data class UnmatchedRom(
    val filePath: String,
    val fileName: String,
    val detectedPlatformId: String?,    // null = truly unknown
)

enum class ScanType { NEW_FILES_ONLY, FULL_RESCAN }

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
)

private val PC_EXPORT_EXTENSIONS = setOf("steam", "epic", "gog", "amazon", "pcgame", "desktop")

@Singleton
class RomScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val platformExtensionMap: PlatformExtensionMap,
    private val discImageResolver: DiscImageResolver,
    private val folderHintResolver: PlatformFolderHintResolver,
) {
    private val defaultFolders = listOf(
        "/storage/emulated/0/ROMs",
        "/storage/emulated/0/Games",
        "/storage/emulated/0/RetroArch/roms",
        "/storage/emulated/0/PPSSPP/PSP/GAME",
        "/sdcard/ROMs",
    )

    // Scan is ALWAYS user-initiated — never called automatically
    fun scan(
        folders: List<String>,
        scanType: ScanType,
        existingRomPaths: Set<String>,
    ): Flow<ScanResult> = flow {
        Timber.i("ROM scan started — type=$scanType, folders=${folders.size}")

        val newGames               = mutableListOf<Game>()
        val unmatched              = mutableListOf<UnmatchedRom>()
        val requiresUserAssignment = mutableListOf<UnmatchedRom>()
        var alreadyInLibrary       = 0
        var filesScanned           = 0

        val foldersToScan = folders.ifEmpty { defaultFolders }

        for (folderPath in foldersToScan) {
            val folder = File(folderPath)
            if (!folder.exists() || !folder.isDirectory) {
                Timber.w("Scan folder not found: $folderPath")
                continue
            }

            // ── Phase 1: Resolve disc images before touching anything else ──
            // This produces the authoritative launch file for every disc-based game
            // and the full set of companion files that must be suppressed.
            val discResolution = discImageResolver.resolveFolder(folder)

            // Add resolved disc games (.cue → PS1, orphan .bin → Mega Drive)
            for (disc in discResolution.resolvedDiscs) {
                if (scanType == ScanType.NEW_FILES_ONLY &&
                    disc.launchFile.absolutePath in existingRomPaths) {
                    alreadyInLibrary++
                    continue
                }
                newGames.add(
                    Game(
                        title      = disc.launchFile.nameWithoutExtension.sanitizeRomName(),
                        platformId = disc.platformId,
                        romPath    = disc.launchFile.absolutePath,
                    )
                )
                Timber.d("Disc game added: ${disc.launchFile.name} → platform=${disc.platformId}")
            }

            // .chd / .img: try folder hint first, fall back to user assignment
            for (ambiguousFile in discResolution.requiresUserAssignment) {
                val hint = folderHintResolver.detectFromPath(ambiguousFile.absolutePath)
                if (hint != null) {
                    if (scanType == ScanType.NEW_FILES_ONLY &&
                        ambiguousFile.absolutePath in existingRomPaths) {
                        alreadyInLibrary++
                        continue
                    }
                    newGames.add(
                        Game(
                            title      = ambiguousFile.nameWithoutExtension.sanitizeRomName(),
                            platformId = hint,
                            romPath    = ambiguousFile.absolutePath,
                        )
                    )
                    Timber.d("Folder-hinted ambiguous disc: ${ambiguousFile.name} → $hint")
                } else {
                    requiresUserAssignment.add(
                        UnmatchedRom(
                            filePath           = ambiguousFile.absolutePath,
                            fileName           = ambiguousFile.name,
                            detectedPlatformId = null,
                        )
                    )
                }
            }

            // ── Phase 2: Scan definitive + folder-sensitive extension files ──
            val allFiles = folder.walkTopDown()
                .filter { it.isFile &&
                    (platformExtensionMap.isDefinitive(it.extension) ||
                     platformExtensionMap.isFolderSensitive(it.extension)) }
                .toList()

            for (file in allFiles) {
                filesScanned++

                // Skip if suppressed by disc resolver (companion .bin files)
                if (file.absolutePath in discResolution.suppressedPaths) continue

                if (scanType == ScanType.NEW_FILES_ONLY &&
                    file.absolutePath in existingRomPaths) {
                    alreadyInLibrary++
                    continue
                }

                emit(ScanResult.Progress(
                    ScanProgress(
                        currentFolder  = folderPath,
                        filesScanned   = filesScanned,
                        filesFound     = newGames.size,
                        totalEstimated = allFiles.size,
                    )
                ))

                // For folder-sensitive extensions (.iso), let parent folder name win;
                // fall back to the extension's default platform.
                val platformId = if (platformExtensionMap.isFolderSensitive(file.extension)) {
                    val hint = folderHintResolver.detectFromPath(file.absolutePath)
                    if (hint != null) {
                        Timber.d("Folder-hinted: ${file.name} → $hint")
                        hint
                    } else {
                        platformExtensionMap.folderSensitiveDefault(file.extension)
                    }
                } else {
                    platformExtensionMap.detectPlatform(file.extension)
                }

                if (platformId != null) {
                    newGames.add(
                        Game(
                            title      = file.nameWithoutExtension.sanitizeRomName(),
                            platformId = platformId,
                            romPath    = file.absolutePath,
                        )
                    )
                } else {
                    unmatched.add(
                        UnmatchedRom(
                            filePath           = file.absolutePath,
                            fileName           = file.name,
                            detectedPlatformId = null,
                        )
                    )
                }
            }
        }

        Timber.i(
            "ROM scan complete — " +
            "new=${newGames.size}, " +
            "unmatched=${unmatched.size}, " +
            "needsAssignment=${requiresUserAssignment.size}, " +
            "existing=$alreadyInLibrary"
        )

        emit(ScanResult.Complete(newGames, alreadyInLibrary, unmatched, requiresUserAssignment))

    }.flowOn(Dispatchers.IO)

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

        val candidates = (if (recursive) root.walkTopDown() else root.listFiles()?.asSequence() ?: emptySequence())
            .filter { it.isFile }
            .filter { !it.name.startsWith(".") }                 // ignore hidden files
            .filter { it.extension.lowercase() in allowed }      // only supported extensions
            .toList()

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

            newGames.add(
                Game(
                    title      = cleanRomTitle(file.nameWithoutExtension),
                    platformId = platformId,
                    romPath    = path,
                )
            )
        }

        Timber.i("Memory Card scan complete — platform=$platformId new=${newGames.size} existing=$alreadyInLibrary")
        emit(ScanResult.Complete(newGames, alreadyInLibrary, emptyList(), emptyList()))

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
        var alreadyInLibrary = 0
        var filesScanned     = 0

        // Iterative DFS over document IDs; visited-set guards a cyclic/hostile provider. The start
        // is the requested subfolder (single-root model) or the tree's own root document.
        val visited = HashSet<String>()
        val rootDocId = startDocId ?: DocumentsContract.getTreeDocumentId(tree)
        visited.add(rootDocId)
        val stack = ArrayDeque<String>()
        stack.addLast(rootDocId)
        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val dirDocId = stack.removeLast()
            for (child in context.contentResolver.querySafChildren(tree, dirDocId)) {
                coroutineContext.ensureActive()
                if (child.isDirectory) {
                    if (recursive && visited.add(child.documentId)) stack.addLast(child.documentId)
                    continue
                }
                if (child.name.startsWith(".")) continue
                val ext = child.name.substringAfterLast('.', "").lowercase()
                if (ext !in allowed) continue

                filesScanned++
                // Derive the raw path from the document id (pure string math, no file access) so it
                // stays the stable dedupe key and the {rom_path} value.
                val rawPath = safDocumentIdToRawPath(child.documentId) ?: child.uri.toString()
                if (rawPath in existingRomPaths || !seenPaths.add(rawPath)) {
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

                newGames.add(
                    Game(
                        title      = cleanRomTitle(child.name.substringBeforeLast('.', child.name)),
                        platformId = platformId,
                        romPath    = rawPath,
                        romUri     = child.uri.toString(),
                    )
                )
            }
        }

        Timber.i("Memory Card SAF scan complete — platform=$platformId new=${newGames.size} existing=$alreadyInLibrary")
        emit(ScanResult.Complete(newGames, alreadyInLibrary, emptyList(), emptyList()))

    }.flowOn(Dispatchers.IO)

    // Immediate child directory names of a granted tree — the ES-DE system folders directly under
    // the ROM root (e.g. "gba", "snes", "psx"). One ContentResolver query; files are ignored.
    // Drives the single-scan autoload: each name is mapped to a platform by PlatformFolderHintResolver.
    suspend fun listSubfolderNames(treeUri: String): List<String> = withContext(Dispatchers.IO) {
        val tree = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return@withContext emptyList()
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?: return@withContext emptyList()
        context.contentResolver.querySafChildren(tree, rootDocId)
            .filter { it.isDirectory }
            .map { it.name }
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
                    val idContent = if (ext == "desktop") null else runCatching {
                        context.contentResolver.openInputStream(child.uri)
                            ?.use { it.readBytes().toString(Charsets.UTF_8).trim() }
                    }.getOrNull()
                    out.add(
                        PcExportFile(
                            title      = title,
                            extension  = ext,
                            idContent  = idContent,
                            rawPath    = safDocumentIdToRawPath(child.documentId),
                            uri        = child.uri.toString(),
                        )
                    )
                }
            }
            Timber.i("PC folder scan — found ${out.size} export file(s)")
            out
        }

    suspend fun findMissingRoms(knownPaths: List<String>): List<String> =
        knownPaths.filter { path -> !File(path).exists() }

    private fun String.sanitizeRomName(): String = cleanRomTitle(this)
}

// Converts a SAF externalstorage document id ("primary:ROMs/game.iso", "1A2B-3C4D:Games/game.iso")
// to its raw filesystem path. Pure string math — needs no storage access and is safe to derive even
// without MANAGE_EXTERNAL_STORAGE. Returns null for non-volume document ids (kept as a fallback by
// the caller). Mirrors the derivation used when a card's folder is first picked.
fun safDocumentIdToRawPath(documentId: String): String? {
    val parts = documentId.split(":", limit = 2)
    if (parts.size != 2 || parts[1].isBlank()) return null
    val (volume, relative) = parts
    return if (volume.equals("primary", ignoreCase = true)) {
        "/storage/emulated/0/$relative"
    } else {
        "/storage/$volume/$relative"
    }
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
