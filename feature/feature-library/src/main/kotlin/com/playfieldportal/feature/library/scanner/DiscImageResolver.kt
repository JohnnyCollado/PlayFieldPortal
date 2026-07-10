package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.data.platform.PlatformFolderHintResolver
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// Resolves disc-based game formats where multiple files represent one game.
//
// .cue + .bin(s) → disc game; folder hint picks platform (psx/saturn/pcengine),
//                  falls back to "psx" since .cue is most common for PS1
// .bin alone      → folder hint picks platform, falls back to "megadrive"
// .chd / .img     → flagged for caller to resolve via folder hint or user input
@Singleton
class DiscImageResolver @Inject constructor(
    private val folderHintResolver: PlatformFolderHintResolver,
) {

    data class ResolvedDisc(
        val launchFile: File,
        val platformId: String,
        val suppressedFiles: Set<File>, // companion files that must not be counted separately
    )

    data class FolderResolution(
        val resolvedDiscs: List<ResolvedDisc>,
        val suppressedPaths: Set<String>,       // absolute paths — scanner skips these
        val requiresUserAssignment: List<File>, // .chd/.img with no context
    )

    fun resolveFolder(folder: File): FolderResolution {
        val allFiles = folder.walkTopDown().filter { it.isFile }.toList()
        return resolveFiles(allFiles)
    }

    fun resolveFiles(files: List<File>): FolderResolution {
        val resolvedDiscs      = mutableListOf<ResolvedDisc>()
        val suppressedPaths    = mutableSetOf<String>()
        val requiresAssignment = mutableListOf<File>()

        val byDir = files.groupBy { it.parentFile?.absolutePath ?: "" }

        for ((_, siblings) in byDir) {
            val cueFiles = siblings.filter { it.extension.lowercase() == "cue" }
            val binFiles = siblings.filter { it.extension.lowercase() == "bin" }
            val chdFiles = siblings.filter { it.extension.lowercase() == "chd" }
            val imgFiles = siblings.filter { it.extension.lowercase() == "img" }

            // ── .cue + .bin → disc game; platform from folder hint ────────
            for (cueFile in cueFiles) {
                val companionBins = findCompanionBins(cueFile, binFiles)
                val platformId = folderHintResolver.detectFromPath(cueFile.absolutePath) ?: "psx"

                resolvedDiscs.add(
                    ResolvedDisc(
                        launchFile = cueFile,
                        platformId = platformId,
                        suppressedFiles = companionBins.toSet(),
                    )
                )

                // All companion .bin files are suppressed — they are not games
                companionBins.forEach { suppressedPaths.add(it.absolutePath) }

                Timber.d(
                    "Disc resolved: ${cueFile.name} (PS1) — " +
                    "suppressed ${companionBins.size} companion .bin file(s)"
                )
            }

            // ── Orphan .bin (no sibling .cue) → Mega Drive ──────────────
            val claimedBins = resolvedDiscs
                .flatMap { it.suppressedFiles }
                .map { it.absolutePath }
                .toSet()

            for (binFile in binFiles) {
                if (binFile.absolutePath in claimedBins) continue // already handled above

                val hasCueSibling = cueFiles.any { cue ->
                    cue.parentFile?.absolutePath == binFile.parentFile?.absolutePath
                }

                if (!hasCueSibling) {
                    val platformId = folderHintResolver.detectFromPath(binFile.absolutePath) ?: "megadrive"
                    resolvedDiscs.add(
                        ResolvedDisc(
                            launchFile = binFile,
                            platformId = platformId,
                            suppressedFiles = emptySet(),
                        )
                    )
                    Timber.d("Orphan .bin resolved as $platformId: ${binFile.name}")
                }
            }

            // ── .chd → platform unknown, needs user assignment ────────────
            // .chd is a compressed disc format used by PS1, PS2, Saturn, GC, Wii
            // We cannot reliably detect platform without reading the disc header
            for (chdFile in chdFiles) {
                requiresAssignment.add(chdFile)
                Timber.d(".chd flagged for user platform assignment: ${chdFile.name}")
            }

            // ── .img alone → platform unknown ────────────────────────────
            for (imgFile in imgFiles) {
                requiresAssignment.add(imgFile)
                Timber.d(".img flagged for user platform assignment: ${imgFile.name}")
            }
        }

        return FolderResolution(
            resolvedDiscs = resolvedDiscs,
            suppressedPaths = suppressedPaths,
            requiresUserAssignment = requiresAssignment,
        )
    }

    /**
     * Finds .bin files that belong to a given .cue by reading the CUE sheet's
     * FILE references. Falls back to name-matching if the CUE is unreadable.
     */
    private fun findCompanionBins(cueFile: File, candidates: List<File>): List<File> {
        val referencedNames = parseCueFileReferences(cueFile)

        return if (referencedNames.isNotEmpty()) {
            // Authoritative: use what the .cue sheet declares
            candidates.filter { it.name in referencedNames }
        } else {
            // Fallback: same directory, same base name, different track suffix
            // e.g. "Game (Track 1).bin", "Game (Track 2).bin" → all belong to "Game.cue"
            val cueBaseName = cueFile.nameWithoutExtension.lowercase()
            candidates.filter { bin ->
                bin.parentFile?.absolutePath == cueFile.parentFile?.absolutePath &&
                bin.nameWithoutExtension.lowercase().let { binBase ->
                    binBase == cueBaseName ||
                    binBase.startsWith(cueBaseName) ||
                    binBase.replace(Regex("\\s*track\\s*\\d+", RegexOption.IGNORE_CASE), "").trim() == cueBaseName
                }
            }
        }
    }

    /**
     * Reads the CUE sheet and extracts all FILE references.
     * CUE format: FILE "filename.bin" BINARY
     */
    private fun parseCueFileReferences(cueFile: File): Set<String> {
        return try {
            cueFile.readLines()
                .filter { it.trimStart().startsWith("FILE", ignoreCase = true) }
                .mapNotNull { line ->
                    // Extract filename between quotes
                    val start = line.indexOf('"')
                    val end   = line.lastIndexOf('"')
                    if (start >= 0 && end > start) line.substring(start + 1, end) else null
                }
                .toSet()
        } catch (e: Exception) {
            Timber.w(e, "Could not parse CUE sheet: ${cueFile.name} — falling back to name matching")
            emptySet()
        }
    }
}
