package com.playfieldportal.studio.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import java.util.prefs.Preferences

/**
 * Theme Studio in the user's language.
 *
 * Text is translated where it is drawn, not where it is written: the Studio's own [Text] passes every
 * string through [tr], so the panels keep their plain English strings and nothing has to be threaded
 * through them. A language file (`resources/i18n/<tag>.tsv`) is a list of `English<TAB>translation`
 * lines. An English line may hold numbered gaps (`Showing {0} of {1}`): it then matches any text of
 * that shape, and the translation says where each piece goes, in whatever order the language needs.
 *
 * Anything with no line in the file is shown in English, so a missing translation is never a blank
 * and a file name or a theme's own name passes through untouched.
 *
 * What is not translated: the preview (it shows the launcher's screens in English), the names of
 * icon slots, and rare error details.
 */
data class StudioLanguage(val tag: String, val nativeName: String)

object StudioI18n {
    /** English first, then the 22 languages the launcher ships, by their own names. */
    val LANGUAGES: List<StudioLanguage> = listOf(
        StudioLanguage("en", "English"),
        StudioLanguage("ar", "العربية"),
        StudioLanguage("bs", "Bosanski"),
        StudioLanguage("ca", "Català"),
        StudioLanguage("cs", "Čeština"),
        StudioLanguage("de", "Deutsch"),
        StudioLanguage("es", "Español"),
        StudioLanguage("fr", "Français"),
        StudioLanguage("hr", "Hrvatski"),
        StudioLanguage("it", "Italiano"),
        StudioLanguage("ja", "日本語"),
        StudioLanguage("ko", "한국어"),
        StudioLanguage("nl", "Nederlands"),
        StudioLanguage("pl", "Polski"),
        StudioLanguage("pt-BR", "Português (Brasil)"),
        StudioLanguage("pt-PT", "Português (Portugal)"),
        StudioLanguage("ro", "Română"),
        StudioLanguage("ru", "Русский"),
        StudioLanguage("sr", "Српски"),
        StudioLanguage("sv", "Svenska"),
        StudioLanguage("vi", "Tiếng Việt"),
        StudioLanguage("zh-CN", "简体中文"),
        StudioLanguage("zh-TW", "繁體中文"),
    )

    private const val PREF_KEY = "language"
    private val prefs: Preferences? = runCatching { Preferences.userRoot().node("com/playfieldportal/studio/i18n") }.getOrNull()

    /** The language in use. A Compose state, so everything drawn re-reads its text when it changes. */
    var tag: String by mutableStateOf(startingTag())
        private set

    /** Switches language and remembers the choice on this computer. */
    fun choose(newTag: String) {
        if (LANGUAGES.none { it.tag == newTag }) return
        tag = newTag
        runCatching { prefs?.put(PREF_KEY, newTag) }
    }

    /** Switches language for this run only. For tests and renders. */
    fun useForNow(newTag: String) {
        if (LANGUAGES.any { it.tag == newTag }) tag = newTag
    }

    val current: StudioLanguage get() = LANGUAGES.first { it.tag == tag }

    private fun startingTag(): String {
        val saved = runCatching { prefs?.get(PREF_KEY, null) }.getOrNull()
        if (saved != null && LANGUAGES.any { it.tag == saved }) return saved
        return tagFor(Locale.getDefault())
    }

    /** The shipped language closest to [locale]: an exact region match, else the language, else English. */
    fun tagFor(locale: Locale): String {
        val language = locale.language.lowercase()
        val region = locale.country.uppercase()
        val script = locale.script
        return when (language) {
            "pt" -> if (region == "PT") "pt-PT" else "pt-BR"
            "zh" -> if (region in setOf("TW", "HK", "MO") || script == "Hant") "zh-TW" else "zh-CN"
            else -> LANGUAGES.firstOrNull { it.tag == language }?.tag ?: "en"
        }
    }

    private val tables = HashMap<String, Table>()

    internal fun table(forTag: String): Table = synchronized(tables) {
        tables.getOrPut(forTag) { Table.load(forTag) }
    }
}

/** One language's lines: the plain ones, and the ones with gaps as patterns, most specific first. */
internal class Table(private val exact: Map<String, String>, private val shaped: List<Shaped>) {
    internal class Shaped(val regex: Regex, val translation: String, val weight: Int)

    private val remembered = HashMap<String, String>()

    fun line(text: String): String {
        exact[text]?.let { return it }
        synchronized(remembered) { remembered[text]?.let { return it } }
        var result = text
        for (shape in shaped) {
            val match = shape.regex.matchEntire(text) ?: continue
            // A captured piece is itself translated when it is a known phrase (a section's name
            // inside "Open: {0}"), and left alone otherwise (a number, a file name).
            result = GAP.replace(shape.translation) { gap ->
                val piece = match.groupValues.getOrNull(gap.groupValues[1].toInt() + 1) ?: return@replace gap.value
                exact[piece] ?: piece
            }
            break
        }
        synchronized(remembered) { if (remembered.size < 4000) remembered[text] = result }
        return result
    }

    companion object {
        private val GAP = Regex("""\{(\d+)\}""")
        val EMPTY = Table(emptyMap(), emptyList())

        fun load(tag: String): Table {
            if (tag == "en") return EMPTY
            val stream = Table::class.java.classLoader.getResourceAsStream("i18n/$tag.tsv") ?: return EMPTY
            val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            return parse(text)
        }

        fun parse(text: String): Table {
            val exact = HashMap<String, String>()
            val shaped = ArrayList<Shaped>()
            for (raw in text.lineSequence()) {
                val line = raw.trimEnd('\r')
                if (line.isBlank() || line.startsWith("#")) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val english = line.substring(0, tab)
                val translation = line.substring(tab + 1)
                if (translation.isEmpty()) continue
                if (!GAP.containsMatchIn(english)) {
                    exact[english] = translation
                    continue
                }
                // The fixed words around the gaps must be substantial, or the line would match anything.
                val fixed = GAP.replace(english, "")
                if (fixed.count { it.isLetter() } < 2) continue
                val pattern = buildString {
                    var from = 0
                    for (gap in GAP.findAll(english)) {
                        append(Regex.escape(english.substring(from, gap.range.first)))
                        append("(.+?)")
                        from = gap.range.last + 1
                    }
                    append(Regex.escape(english.substring(from)))
                }
                shaped += Shaped(Regex(pattern), translation, fixed.length)
            }
            shaped.sortByDescending { it.weight }
            return Table(exact, shaped)
        }
    }
}

/**
 * [text] in the language in use. Each line of a multi-line text is looked up on its own, so a
 * message built from several lines translates the lines it knows.
 */
fun tr(text: String): String {
    val tag = StudioI18n.tag // read first: this is what makes a composable re-run on a language change
    if (tag == "en" || text.isEmpty()) return text
    val table = StudioI18n.table(tag)
    if ('\n' !in text) return table.line(text)
    return text.split('\n').joinToString("\n") { if (it.isBlank()) it else table.line(it) }
}
