package com.playfieldportal.feature.artwork.match

import java.util.Locale

/**
 * The canonical match form — pass 5's key (C22 task T2). Pure JVM, no Android types.
 *
 * **Why this is not in `ArtworkNaming`.** That object's rules are frozen at
 * `NORMALIZATION_VERSION = 1` and its `slug()` is what becomes a SAF directory name: editing
 * `normalizeForMatch` or `simplifyTitle` would rename folders on disk for every existing user.
 * These rules are therefore matching-only and live apart, and nothing `slug()` calls may reach
 * them. `ArtworkNamingTest` pins that separation.
 *
 * Applied to the output of `ArtworkNaming.simplifyTitle`, which has already dropped release tags,
 * expanded `&` and unified punctuation. Two rules are added on top:
 *
 *  1. **Article.** Every standalone `the` token is dropped. This is the No-Intro/Redump
 *     trailing-article convention: `Legend of Zelda, The` and `The Legend of Zelda` are the same
 *     game spelled two ways, and before pass 5 they did not match. English only — stripping
 *     German `die` would wreck `Die Hard Trilogy`.
 *
 *     **Why every position, not just the ends.** The convention moves the article to the end of
 *     the *main* title, not the end of the string: `Legend of Zelda, The - Ocarina of Time`. By
 *     the time `simplifyTitle` has run, the comma and the dash are both gone, so nothing here can
 *     tell that `the` was at a segment boundary — an end-anchored rule silently misses every
 *     subtitled game, which is most of them. Dropping the token wherever it appears is symmetric:
 *     it is applied to the filename and to the game's own titles alike, so two spellings of one
 *     title still meet. The cost is merging two genuinely distinct titles that differ only by a
 *     `the`, which the uniqueness valve turns into a review item rather than a wrong link.
 *  2. **Numerals.** A standalone roman-numeral token becomes arabic, so `Final Fantasy VII`
 *     meets `Final Fantasy 7`. Bare `i`, `v` and `x` are included deliberately, which merges
 *     `Mega Man X` with `Mega Man 10`. That collision is accepted, not overlooked: pass 5
 *     auto-matches only a unique candidate, so a library holding both yields `Ambiguous` — a
 *     review item, never wrong artwork.
 *
 * There is deliberately **no** whitespace-stripping rule and no similarity scoring. Pass 5 is an
 * equality pass on a stricter key, like every pass above it.
 */
object TitleCanon {

    private val WHITESPACE = Regex("""\s+""")

    /** Roman numerals 1..39 as a whole token — enough for any game title, and cheap to verify. */
    private val ROMAN = Regex("""^(x{0,3})(ix|iv|v?i{0,3})$""")

    /**
     * The canonical form of [simplified], which is expected to be `ArtworkNaming.simplifyTitle`
     * output. Blank in, blank out. Idempotent: `of(of(x)) == of(x)`.
     */
    fun of(simplified: String): String {
        if (simplified.isBlank()) return ""
        return simplified.lowercase(Locale.ROOT)
            .split(WHITESPACE)
            .filter { it.isNotBlank() && it != ARTICLE }
            .joinToString(" ") { romanToArabic(it) ?: it }
    }

    /**
     * [token] as an arabic numeral when it is a whole roman numeral, else null.
     *
     * The regex rejects the empty match the alternation would otherwise allow, so an ordinary
     * word is never mistaken for a numeral — `mix` is not `m`+`ix`, because the match is anchored
     * over the whole token.
     */
    private fun romanToArabic(token: String): String? {
        val match = ROMAN.matchEntire(token) ?: return null
        val tens = match.groupValues[1].length * 10
        val units = when (val u = match.groupValues[2]) {
            "" -> 0
            "ix" -> 9
            "iv" -> 4
            else -> if (u.startsWith("v")) 5 + (u.length - 1) else u.length
        }
        val total = tens + units
        return if (total == 0) null else total.toString()
    }

    private const val ARTICLE = "the"
}
