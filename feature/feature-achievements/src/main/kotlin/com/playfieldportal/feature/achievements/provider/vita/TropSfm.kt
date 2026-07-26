package com.playfieldportal.feature.achievements.provider.vita

/**
 * Reader for a Vita trophy set's `TROP.SFM` — the XML that names/describes each trophy and gives its
 * grade (`ttype` P/G/S/B) and hidden flag. Paired with [TropUsrParser] (which supplies unlock state)
 * to build the full trophy list. Tolerant: a leading signature comment and unknown tags are ignored.
 */
object TropSfm {

    data class TrophyDef(
        val id: Int,
        val name: String,
        val detail: String,
        val grade: TropUsrParser.Grade,
        val hidden: Boolean,
    )

    data class TropSet(
        val npCommId: String?,
        val titleName: String?,
        val trophies: List<TrophyDef>,
    )

    private val TROPHY = Regex("<trophy\\b([^>]*)>(.*?)</trophy>", RegexOption.DOT_MATCHES_ALL)

    fun parse(bytes: ByteArray): TropSet = parse(String(bytes, Charsets.UTF_8))

    fun parse(xml: String): TropSet {
        val trophies = TROPHY.findAll(xml).mapNotNull { m ->
            val attrs = m.groupValues[1]
            val body = m.groupValues[2]
            val id = attr(attrs, "id")?.toIntOrNull() ?: return@mapNotNull null
            TrophyDef(
                id = id,
                name = decode(tag(body, "name").orEmpty()),
                detail = decode(tag(body, "detail").orEmpty()),
                grade = gradeOf(attr(attrs, "ttype")),
                hidden = attr(attrs, "hidden").equals("yes", ignoreCase = true),
            )
        }.toList()
        return TropSet(
            npCommId = tag(xml, "npcommid")?.trim(),
            titleName = tag(xml, "title-name")?.let(::decode),
            trophies = trophies,
        )
    }

    private fun gradeOf(ttype: String?): TropUsrParser.Grade = when (ttype?.uppercase()) {
        "P" -> TropUsrParser.Grade.PLATINUM
        "G" -> TropUsrParser.Grade.GOLD
        "S" -> TropUsrParser.Grade.SILVER
        "B" -> TropUsrParser.Grade.BRONZE
        else -> TropUsrParser.Grade.UNKNOWN
    }

    private fun tag(scope: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(scope)?.groupValues?.get(1)

    private fun attr(attrs: String, name: String): String? =
        Regex("$name\\s*=\\s*\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)

    // Minimal XML entity decode (named + numeric) for names/details.
    private fun decode(s: String): String {
        if ('&' !in s) return s.trim()
        val numeric = Regex("&#(x?[0-9a-fA-F]+);").replace(s) { m ->
            val raw = m.groupValues[1]
            val code = if (raw.startsWith("x") || raw.startsWith("X")) {
                raw.drop(1).toIntOrNull(16)
            } else {
                raw.toIntOrNull()
            }
            code?.let { String(Character.toChars(it)) } ?: m.value
        }
        return numeric
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .trim()
    }
}
