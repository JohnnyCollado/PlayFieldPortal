package com.playfieldportal.feature.achievements.provider.steam

/**
 * Extracts achievement title -> description pairs from a Steam Community achievements page.
 *
 * Deliberately conservative: this is untrusted HTML, so every extracted string is tag-stripped,
 * entity-unescaped, whitespace-normalized, and length-capped before use, and a title that appears
 * twice with different descriptions is dropped as ambiguous. The markup (an `achieveRow` block per
 * achievement with an `<h3>` title and `<h5>` description) has been stable for years, but a Valve
 * redesign simply yields an empty map — callers fall back to the redacted placeholder, never fail.
 */
internal object SteamCommunityAchievementsParser {

    private val ROW = Regex("""class="achieveRow""", RegexOption.IGNORE_CASE)
    private val H3 = Regex("""<h3[^>]*>(.*?)</h3>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val H5 = Regex("""<h5[^>]*>(.*?)</h5>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG = Regex("""<[^>]*>""")

    private const val MAX_DESCRIPTION_CHARS = 500
    private const val MAX_TITLE_CHARS = 200

    /**
     * Title (normalized via [normalizeTitle]) -> sanitized description, for every achievement row
     * the page lists with both. Empty on unrecognized markup.
     */
    fun parse(html: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val ambiguous = mutableSetOf<String>()
        // Everything after each achieveRow marker up to the next one is that achievement's block.
        val starts = ROW.findAll(html).map { it.range.first }.toList()
        starts.forEachIndexed { i, start ->
            val end = if (i + 1 < starts.size) starts[i + 1] else html.length
            val block = html.substring(start, end)
            val title = H3.find(block)?.groupValues?.get(1)?.let(::sanitize)?.take(MAX_TITLE_CHARS)
            val description = H5.find(block)?.groupValues?.get(1)?.let(::sanitize)?.take(MAX_DESCRIPTION_CHARS)
            if (title.isNullOrBlank() || description.isNullOrBlank()) return@forEachIndexed
            val key = normalizeTitle(title)
            val existing = out[key]
            when {
                key in ambiguous -> Unit
                existing == null -> out[key] = description
                existing != description -> { out.remove(key); ambiguous.add(key) }
            }
        }
        return out
    }

    /** Join key for matching a page row to a schema title: case/whitespace-insensitive. */
    fun normalizeTitle(title: String): String = title.trim().lowercase().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("""\s+""")

    // Untrusted HTML -> plain display text: strip tags, unescape the entities Valve emits, collapse
    // whitespace. Never interpreted further and never used to build a request.
    private fun sanitize(raw: String): String = raw
        .replace(TAG, "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#039;", "'")
        .replace("&nbsp;", " ")
        .replace(WHITESPACE, " ")
        .trim()
}
