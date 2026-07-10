package com.playfieldportal.feature.artwork.portable

/**
 * Portable filenames for library layout v2 — the human-readable, frontend-compatible name an
 * asset carries inside `{platform}/{mediaDir}/`.
 *
 * Naming mode is Match-ROM-Filename (the only mode in V1): the ROM filename stem is preserved
 * VERBATIM — case, spaces, parentheses, region/revision/disc tags — because that exact string
 * is what ES-DE and friends match against, and tags are what keep releases collision-free.
 * Only characters that are genuinely illegal on FAT/exFAT/SAF are sanitized.
 *
 * Security: output can never contain a path separator or control character, never starts with
 * a dot (no hidden files, no `..`), avoids Windows reserved device names, and is length-capped.
 */
object PortableNameResolver {

    const val MAX_LENGTH = 150

    // Illegal on FAT/exFAT/NTFS and/or rejected by SAF providers. Spaces, parentheses, dashes
    // and unicode are deliberately NOT touched — they ARE the portability other frontends
    // match against. Control characters are dropped separately below.
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|]")

    private val WHITESPACE = Regex("\\s+")

    private val RESERVED_NAMES = buildSet {
        addAll(listOf("con", "prn", "aux", "nul"))
        (1..9).forEach { add("com$it"); add("lpt$it") }
    }

    /**
     * The portable name for a game, from its ROM filename stem (preferred) or display title.
     * "Final Fantasy X (USA).chd" → "Final Fantasy X (USA)".
     */
    fun fromRomFileName(romFileName: String): String =
        sanitize(ArtworkNaming.fileStem(romFileName))

    fun fromTitle(title: String): String = sanitize(title)

    fun sanitize(raw: String): String {
        val cleaned = raw
            .filterNot { it.isISOControl() }
            .replace(ILLEGAL, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .trimStart('.')                          // no hidden files / traversal shapes
            .trimEnd('.', ' ')                       // Windows rejects trailing dots/spaces
        val capped = cleaned.take(MAX_LENGTH).trimEnd('.', ' ')
        val safe = capped.ifBlank { "untitled" }
        return if (safe.lowercase() in RESERVED_NAMES) "$safe-x" else safe
    }
}
