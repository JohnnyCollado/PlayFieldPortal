package com.playfieldportal.feature.artwork.portable

import java.text.Normalizer
import java.util.Locale

/**
 * The frozen normalization + slug rules for the portable artwork library and the import matcher.
 * Pure JVM (unit-testable), deliberately free of Android types.
 *
 * These rules are versioned ([NORMALIZATION_VERSION], recorded in the library manifest) and must
 * not change once shipped — existing folders stop matching if slugs drift. New behavior requires
 * a version bump plus a compatibility matcher stage, never an in-place rewrite.
 *
 * Security: [slug] output is what becomes a SAF directory name. It is restricted to
 * `[a-z0-9._-]`, can never contain a path separator, never starts with a dot (no hidden files,
 * no `..` traversal), avoids Windows reserved device names (FAT32/exFAT SD cards), and is
 * length-capped — a hostile or degenerate display name cannot escape the library tree.
 */
object ArtworkNaming {

    const val NORMALIZATION_VERSION = 1

    /** Longest slug we mint — safe for FAT32 display names with room for collision suffixes. */
    const val MAX_SLUG_LENGTH = 100

    // Windows/FAT reserved device names — a bare "con" directory breaks the tree on many hosts.
    private val RESERVED_NAMES = buildSet {
        addAll(listOf("con", "prn", "aux", "nul"))
        (1..9).forEach { add("com$it"); add("lpt$it") }
    }

    // Apostrophe/quote variants collapsed before matching ("Ratchet's" == "Ratchet’s").
    private val APOSTROPHES = Regex("[’‘´`]")

    // Release/dump tags: any (...) or [...] group — "(USA)", "(Rev 1)", "[!]", "(Disc 1)" etc.
    private val TAG_GROUPS = Regex("""\(([^)]*)\)|\[([^\]]*)]""")

    private val DISC_TAG = Regex("""\((?:disc|disk|cd)\s*(\d+)[^)]*\)""", RegexOption.IGNORE_CASE)

    private val WHITESPACE = Regex("""\s+""")

    /** Filename minus its final extension ("Jak and Daxter (USA).chd" → "Jak and Daxter (USA)"). */
    fun fileStem(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }

    /**
     * Pass-1 match form: exact-but-forgiving filename identity. Unicode NFKC, lowercase,
     * apostrophes unified, whitespace collapsed, trimmed. Keeps tags — "(USA)" still
     * distinguishes releases at this pass.
     */
    fun normalizeForMatch(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(APOSTROPHES, "'")
            .replace(WHITESPACE, " ")
            .trim()

    /**
     * Pass-3 match form: [normalizeForMatch] with every release tag removed and punctuation
     * unified, so "Jak & Daxter - The Precursor Legacy (USA) [!]" and
     * "Jak and Daxter: The Precursor Legacy" compare equal.
     */
    fun simplifyTitle(name: String): String =
        normalizeForMatch(name)
            .replace(TAG_GROUPS, " ")
            .replace("&", " and ")
            .replace(Regex("""[:\-_.,!?']"""), " ")
            .replace(WHITESPACE, " ")
            .trim()

    /**
     * Folder-safe slug for a game entry. Disc tags survive as a `-discN` suffix (so multi-disc
     * entries don't collide); all other tags are dropped; everything outside `[a-z0-9._-]`
     * becomes `-` with runs collapsed.
     */
    fun slug(stem: String): String {
        val normalized = normalizeForMatch(stem)
        val disc = DISC_TAG.find(normalized)?.groupValues?.get(1)
        val base = normalized
            .replace(TAG_GROUPS, " ")
            .replace(Regex("""[^a-z0-9._-]+"""), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-', '.', '_')
        val withDisc = if (disc != null) "$base-disc$disc" else base
        val capped = withDisc.take(MAX_SLUG_LENGTH).trim('-', '.', '_')
        val safe = capped.ifBlank { "untitled" }
        return if (safe in RESERVED_NAMES) "$safe-x" else safe
    }
}
