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

            // Flag .chd / .img for user assignment
            for (ambiguousFile in discResolution.requiresUserAssignment) {
                requiresUserAssignment.add(
                    UnmatchedRom(
                        filePath           = ambiguousFile.absolutePath,
                        fileName           = ambiguousFile.name,
                        detectedPlatformId = null,
                    )
                )
            }

            // ── Phase 2: Scan all remaining definitive-extension files ──────
            val allFiles = folder.walkTopDown()
                .filter { it.isFile && platformExtensionMap.isDefinitive(it.extension) }
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

                val platformId = platformExtensionMap.detectPlatform(file.extension)

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

    suspend fun findMissingRoms(knownPaths: List<String>): List<String> =
        knownPaths.filter { path -> !File(path).exists() }

    private fun String.sanitizeRomName(): String =
        this.replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace("_", " ")
            .replace("-", " ")
            .trim()
}
