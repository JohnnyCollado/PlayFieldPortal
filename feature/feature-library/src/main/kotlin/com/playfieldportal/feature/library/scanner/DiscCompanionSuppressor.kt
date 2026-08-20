package com.playfieldportal.feature.library.scanner

import javax.inject.Inject
import javax.inject.Singleton

/** One file seen by a scan, addressed by its stable raw path (the dedupe/display key). */
data class ScannedDiscFile(
    val rawPath: String,
    val name: String,
)

/**
 * Computes which scanned files are companions of a disc set — a .bin listed in a sibling .cue, or
 * a track file referenced by a Dreamcast .gdi — so the Memory Card SAF scan path never makes them
 * game rows (docs/plans/multi-disc-games-plan.md step 4). The raw-path counterpart lives in
 * [DiscImageResolver]; this one is SAF-friendly because sheet contents must be read over the
 * granted tree's document URIs, which only the caller knows how to open.
 *
 * Suppression is authoritative-content based: a sheet that can't be read suppresses nothing, and
 * only same-folder siblings are suppressed (a cue never hides a same-named file in another
 * folder). Under-suppressing is safe — the file just stays a row, as it does today.
 */
@Singleton
class DiscCompanionSuppressor @Inject constructor() {

    /** Raw text lines of a sheet file (only called for .cue/.gdi files), or null when unreadable. */
    fun interface SheetReader {
        fun read(file: ScannedDiscFile): List<String>?
    }

    /**
     * Returns the [ScannedDiscFile.rawPath]s that are companions of a sibling sheet. Files outside
     * the sheet's folder are never suppressed.
     */
    fun suppressedFiles(files: List<ScannedDiscFile>, reader: SheetReader): Set<String> {
        val suppressed = mutableSetOf<String>()
        // Folder = the raw path's parent directory, accepting both / and \ separators so the
        // same logic holds for Android (/storage/...) and desktop/Windows-style paths.
        for ((_, folderFiles) in files.groupBy {
            it.rawPath.substringBeforeLast('/').substringBeforeLast('\\')
        }) {
            val byName = folderFiles.associateBy { it.name.lowercase() }
            for (sheet in folderFiles) {
                val ext = sheet.name.substringAfterLast('.', "").lowercase()
                val referenced = when (ext) {
                    "cue" -> reader.read(sheet)?.let(::cueSheetReferences)
                    "gdi" -> reader.read(sheet)?.let(::gdiSheetTrackNames)
                    else -> null
                } ?: continue
                for (name in referenced) {
                    byName[name]?.let { suppressed.add(it.rawPath) }
                }
            }
        }
        return suppressed
    }
}
