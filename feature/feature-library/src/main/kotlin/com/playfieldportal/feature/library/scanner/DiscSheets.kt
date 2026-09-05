package com.playfieldportal.feature.library.scanner

// Pure sheet parsers shared by the raw-path resolver (DiscImageResolver), the SAF companion
// suppressor (DiscCompanionSuppressor), and the region reader (DiscRegionReader), so .cue/.gdi
// companion logic can't drift between paths.
//
// Each format has two variants over one parse: a *Raw list that keeps the names as written in the
// sheet (for callers that must OPEN the referenced file), and a lowercase set derived from it (for
// callers that match names against a sibling listing). Both strip directory components — that
// stripping is the path-traversal guard on untrusted sheet contents, not cosmetics.

/**
 * The FILE entries of a .cue sheet in sheet order, original case, basenames only.
 *
 * For callers that must OPEN the referenced file rather than match it against a listing — the
 * lowercase set below is a comparison key and will not resolve on a case-sensitive volume.
 *
 * Handles both quoted (`FILE "name.bin" BINARY`) and unquoted (`FILE name.bin BINARY`) forms.
 */
fun cueSheetReferencesRaw(lines: List<String>): List<String> {
    val refs = mutableListOf<String>()
    for (line in lines) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("FILE", ignoreCase = true)) continue
        val name = if ('"' in trimmed) {
            val start = trimmed.indexOf('"')
            val end = trimmed.lastIndexOf('"')
            if (end > start) trimmed.substring(start + 1, end) else null
        } else {
            trimmed.removePrefix("FILE").trim().substringBefore(' ').takeIf { it.isNotEmpty() }
        }
        name?.let { refs.add(it.substringAfterLast('/').substringAfterLast('\\')) }
    }
    return refs
}

/** The .gdi track names in sheet order, original case, basenames only. See [cueSheetReferencesRaw]. */
fun gdiSheetTrackNamesRaw(lines: List<String>): List<String> {
    val tracks = mutableListOf<String>()
    for (line in lines) {
        // The filename field may be quoted and contain spaces; tokenise while preserving quotes.
        val fields = Regex("\"(?:\\\\.|[^\"])*\"|\\S+")
            .findAll(line.trim()).map { it.value }.toList()
        if (fields.size < 5) continue
        val name = fields[4].removePrefix("\"").removeSuffix("\"")
        if (name.isNotEmpty()) tracks.add(name.substringAfterLast('/').substringAfterLast('\\'))
    }
    return tracks
}

/**
 * The FILE entries of a .cue sheet, as lowercase basenames ("game (track 1).bin").
 * A comparison key for matching a sheet's references against a sibling listing.
 */
fun cueSheetReferences(lines: List<String>): Set<String> =
    cueSheetReferencesRaw(lines).map { it.lowercase() }.toSet()

/**
 * The track-file names referenced by a Dreamcast .gdi, as lowercase basenames. GDI layout is
 * `track start_msf lba mode size file [pvd]` — the file is the 5th whitespace field, quoted or not.
 */
fun gdiSheetTrackNames(lines: List<String>): Set<String> =
    gdiSheetTrackNamesRaw(lines).map { it.lowercase() }.toSet()
