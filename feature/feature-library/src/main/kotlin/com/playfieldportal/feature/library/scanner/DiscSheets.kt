package com.playfieldportal.feature.library.scanner

// Pure sheet parsers shared by the raw-path resolver (DiscImageResolver) and the SAF companion
// suppressor (DiscCompanionSuppressor), so .cue/.gdi companion logic can't drift between paths.
// Names are normalised to lowercase basenames — the callers match them against sibling files.

/**
 * The FILE entries of a .cue sheet, as lowercase basenames ("game (track 1).bin").
 * Handles both quoted (`FILE "name.bin" BINARY`) and unquoted (`FILE name.bin BINARY`) forms.
 */
fun cueSheetReferences(lines: List<String>): Set<String> {
    val refs = mutableSetOf<String>()
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
        name?.let { refs.add(it.substringAfterLast('/').substringAfterLast('\\').lowercase()) }
    }
    return refs
}

/**
 * The track-file names referenced by a Dreamcast .gdi, as lowercase basenames. GDI layout is
 * `track start_msf lba mode size file [pvd]` — the file is the 5th whitespace field, quoted or not.
 */
fun gdiSheetTrackNames(lines: List<String>): Set<String> {
    val tracks = mutableSetOf<String>()
    for (line in lines) {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 5) continue
        val name = fields[4].trim('"')
        if (name.isNotEmpty()) tracks.add(name.substringAfterLast('/').substringAfterLast('\\').lowercase())
    }
    return tracks
}
