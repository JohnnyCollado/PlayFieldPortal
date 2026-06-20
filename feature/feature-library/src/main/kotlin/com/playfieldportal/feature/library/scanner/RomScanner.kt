package com.playfieldportal.feature.library.scanner

import android.content.Context
import com.playfieldportal.core.domain.model.Game
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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

    suspend fun findMissingRoms(knownPaths: List<String>): List<String> =
        knownPaths.filter { path -> !File(path).exists() }

    private fun String.sanitizeRomName(): String = cleanRomTitle(this)
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
