package com.playfieldportal.feature.library.scanner

/**
 * A disc tag parsed out of a raw ROM filename stem, e.g. `(Disc 1)`, `(Disc 1 of 3)`, `(Disk 1)`,
 * `(CD1)`, `[Disc 2]`, or a trailing `- Disc 1`.
 *
 * The tag becomes structured data instead of discarded text: [discNumber] positions the disc in
 * its set, [discTotal] is the set size when the dump states it (nullable otherwise), and
 * [strippedTitle] is the original title with only the disc tag removed — region/revision tags are
 * left intact for [cleanRomTitle] to handle.
 */
data class DiscTag(
    val discNumber: Int,
    val discTotal: Int?,
    /** The original title with only the disc tag removed (whitespace collapsed). */
    val strippedTitle: String,
)

// A disc tag inside parentheses or brackets: "(Disc 1)", "(Disc 1 of 3)", "(Disk 2)", "(CD1)",
// "(CD 1)", "[Disc 2]". The word is required to be followed by a number, so a title that merely
// contains the word "disc" ("Disc Jam") is never misparsed.
private val TAG_IN_GROUP = Regex(
    """[(\[](?:[^()\[\]]*?)(?:disc|disk|cd)\s*(\d+)(?:\s*of\s*(\d+))?[^()\[\]]*?[)\]]""",
    RegexOption.IGNORE_CASE,
)

// The trailing "- Disc 1" / "– Disc 2 of 3" form used by some dumps.
private val TRAILING_TAG = Regex(
    """[-–—]\s*(?:disc|disk)\s*(\d+)(?:\s*of\s*(\d+))?\s*$""",
    RegexOption.IGNORE_CASE,
)

/**
 * Parses the disc tag out of a raw ROM filename stem ("Final Fantasy VII (Disc 1) (USA)").
 *
 * Returns null when the title carries no disc tag — the game is not (yet) known to be part of a
 * multi-disc set and is left untouched. The tag is parsed before [cleanRomTitle] would strip it,
 * so disc identity survives title cleaning.
 */
fun parseDiscTag(raw: String): DiscTag? {
    val tagInGroup = TAG_IN_GROUP.find(raw)
    if (tagInGroup != null) {
        val (number, total) = parseNumbers(tagInGroup) ?: return null
        val stripped = collapseWhitespace(raw.replaceRange(tagInGroup.range, " "))
        return DiscTag(number, total, stripped)
    }

    val trailing = TRAILING_TAG.find(raw)
    if (trailing != null) {
        val (number, total) = parseNumbers(trailing) ?: return null
        val stripped = collapseWhitespace(raw.removeRange(trailing.range))
        return DiscTag(number, total, stripped)
    }

    return null
}

private fun parseNumbers(match: MatchResult): Pair<Int, Int?>? {
    val number = match.groupValues[1].toIntOrNull() ?: return null
    val total = match.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull()
    return number to total
}

private fun collapseWhitespace(s: String): String =
    s.replace(Regex("\\s+"), " ").trim()
