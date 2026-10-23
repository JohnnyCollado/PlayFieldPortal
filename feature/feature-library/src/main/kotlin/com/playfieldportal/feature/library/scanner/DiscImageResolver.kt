package com.playfieldportal.feature.library.scanner

import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// Finds the files that are parts of a multi-file disc rather than games of their own: the .bin
// tracks a .cue lists and the track files a Dreamcast .gdi lists. The scanner skips these so one
// disc is one row.
//
// It used to also build a list of resolved discs (with a platform guessed per folder) and a list
// of .chd/.img files needing assignment. Only the old folder-list scan read those, and that scan
// had no callers, so both went with it — along with its bug of dropping a stand-alone .bin that
// merely sat next to an unrelated .cue (survey, docs/audits/codebase-survey-2026-10-22.md).
@Singleton
class DiscImageResolver @Inject constructor() {

    data class FolderResolution(
        val suppressedPaths: Set<String>,       // absolute paths — scanner skips these
    )

    /**
     * @param scannableSheets the extensions the caller can actually turn into game rows
     *   (lowercase, no leading dots), or null when every sheet may count. A sheet outside that set
     *   is not a row, so it must not hide its companions either: suppressing them would leave the
     *   disc with no row at all — the .bin a PS2 card can launch, hidden by a .cue the same card
     *   cannot scan (issue #22).
     */
    fun resolveFiles(
        files: List<File>,
        scannableSheets: Set<String>? = null,
    ): FolderResolution {
        val suppressedPaths = mutableSetOf<String>()

        for ((_, siblings) in files.groupBy { it.parentFile?.absolutePath ?: "" }) {
            val binFiles = siblings.filter { it.extension.lowercase() == "bin" }

            // ── .cue + .bin → the bins the sheet lists are parts, not games ──
            for (cueFile in siblings.filter { it.extension.lowercase() == "cue" && it.isScannableSheet(scannableSheets) }) {
                val companionBins = findCompanionBins(cueFile, binFiles)
                companionBins.forEach { suppressedPaths.add(it.absolutePath) }
                Timber.d("Disc ${cueFile.name}: suppressed ${companionBins.size} companion .bin file(s)")
            }

            // ── Dreamcast .gdi → referenced track files suppressed ────────
            // A .gdi references trackNN.bin/.raw files that are parts of one disc, never games in
            // their own right. Suppressed by content (the sheet's track list), so a user-edited
            // extension list that includes bin/raw still produces one row per .gdi.
            for (gdiFile in siblings.filter { it.extension.lowercase() == "gdi" && it.isScannableSheet(scannableSheets) }) {
                val trackNames = gdiSheetTrackNames(readLinesOrEmpty(gdiFile))
                val trackFiles = siblings.filter { it.name.lowercase() in trackNames }
                trackFiles.forEach { suppressedPaths.add(it.absolutePath) }
                if (trackFiles.isNotEmpty()) {
                    Timber.d("GDI tracks suppressed for ${gdiFile.name}: ${trackFiles.size}")
                }
            }
        }

        return FolderResolution(suppressedPaths = suppressedPaths)
    }

    /**
     * Finds .bin files that belong to a given .cue by reading the CUE sheet's
     * FILE references. Falls back to name-matching if the CUE is unreadable.
     */
    private fun findCompanionBins(cueFile: File, candidates: List<File>): List<File> {
        val referencedNames = parseCueFileReferences(cueFile)

        return if (referencedNames.isNotEmpty()) {
            // Authoritative: use what the .cue sheet declares (names are normalised lowercase)
            candidates.filter { it.name.lowercase() in referencedNames }
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
     * Reads the CUE sheet and extracts all FILE references (shared [cueSheetReferences] parser).
     * CUE format: FILE "filename.bin" BINARY
     */
    private fun parseCueFileReferences(cueFile: File): Set<String> {
        return try {
            cueSheetReferences(cueFile.readLines())
        } catch (e: Exception) {
            Timber.w(e, "Could not parse CUE sheet: ${cueFile.name} — falling back to name matching")
            emptySet()
        }
    }

    private fun readLinesOrEmpty(file: File): List<String> = try {
        file.readLines()
    } catch (e: Exception) {
        Timber.w(e, "Could not read sheet: ${file.name}")
        emptyList()
    }

    /** True when [scannableSheets] is null (any sheet counts) or names this file's extension. */
    private fun File.isScannableSheet(scannableSheets: Set<String>?): Boolean =
        scannableSheets == null || extension.lowercase() in scannableSheets
}
