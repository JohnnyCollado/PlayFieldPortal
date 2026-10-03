package com.playfieldportal.core.domain.playlist

import java.util.Locale

/**
 * Names an imported playlist. The name comes from the file, else the picked file's name, and a
 * clash with an existing name gets a ` (n)` suffix so an import never merges into or overwrites
 * another playlist.
 */
object PlaylistImportNaming {

    const val DEFAULT_NAME = "Imported Playlist"

    private val COUNT_SUFFIX = Regex("""^(.*\S)\s+\((\d+)\)$""")

    /** [parsedName] is the in-file name (or null); [fileName] is the picked file's display name. */
    fun baseName(parsedName: String?, fileName: String?): String {
        parsedName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val stem = fileName?.trim()?.substringBeforeLast('.', missingDelimiterValue = fileName.trim())?.trim()
        return stem?.takeIf { it.isNotEmpty() } ?: DEFAULT_NAME
    }

    /**
     * [base] if free, else `Name (2)`, `Name (3)` and so on. Comparison ignores case. A [base] that
     * already ends in ` (n)` continues counting from n.
     */
    fun uniqueName(base: String, takenNames: Collection<String>): String {
        val taken = takenNames.mapTo(HashSet()) { it.trim().lowercase(Locale.ROOT) }
        if (base.trim().lowercase(Locale.ROOT) !in taken) return base
        val match = COUNT_SUFFIX.matchEntire(base)
        val stem = match?.groupValues?.get(1) ?: base
        var n = (match?.groupValues?.get(2)?.toIntOrNull() ?: 1) + 1
        while ("$stem ($n)".lowercase(Locale.ROOT) in taken) n++
        return "$stem ($n)"
    }
}
