package com.playfieldportal.studio

import com.playfieldportal.studio.i18n.StudioI18n
import com.playfieldportal.studio.i18n.tr
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Studio's language files are complete, and text is translated the way the panels rely on. */
class StudioI18nTest {

    private val gap = Regex("""\{\d+\}""")

    @AfterTest
    fun backToEnglish() = StudioI18n.useForNow("en")

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("i18n/$name")) { "missing i18n/$name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun keys(): List<String> = resource("keys.txt").lines().map { it.trimEnd('\r') }.filter { it.isNotBlank() }

    private fun lines(tag: String): Map<String, String> =
        resource("$tag.tsv").lines().map { it.trimEnd('\r') }
            .filter { it.isNotBlank() && !it.startsWith("#") && '\t' in it }
            .associate { it.substringBefore('\t') to it.substringAfter('\t') }

    @Test
    fun `every language has every line, with the same gaps`() {
        val keys = keys()
        assertTrue(keys.size >= 170, "expected the full key list, got ${keys.size}")
        val problems = ArrayList<String>()
        for (language in StudioI18n.LANGUAGES.filter { it.tag != "en" }) {
            val table = lines(language.tag)
            for (key in keys) {
                val translation = table[key]
                if (translation.isNullOrBlank()) {
                    problems += "${language.tag}: missing \"$key\""
                    continue
                }
                val wanted = gap.findAll(key).map { it.value }.sorted().toList()
                val got = gap.findAll(translation).map { it.value }.sorted().toList()
                if (wanted != got) problems += "${language.tag}: gaps differ in \"$key\""
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /**
     * Every text written straight into the Studio's UI code (`Text("…")`, a hint, a heading, a file
     * dialog title) has a line in keys.txt, so a new string cannot slip in untranslated. A `$value`
     * in the code stands for a `{n}` gap in the key. Messages built elsewhere and shown later (status
     * lines, check results) are not reachable this way; keys.txt lists them by hand.
     */
    @Test
    fun `text written into the UI code has a translation line`() {
        val keys = keys().toSet()
        val shapes = keys.filter { gap.containsMatchIn(it) }.map { key ->
            Regex(gap.split(key).joinToString("(.+?)") { Regex.escape(it) })
        }
        val call = Regex("""\b(?:Text|HintText|MutedText|SectionHeading|HeadingWithInfo|openFile|saveFile|pickDirectory|folder|open)\(\s*(?:window,\s*)?"((?:[^"\\]|\\.)*)"""")
        val template = Regex("""\$\{[^}]*\}|\$[A-Za-z_]\w*""")
        val missing = java.io.File("src/main/kotlin/com/playfieldportal/studio/ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                // A sentence split over lines ("…on the " + "launcher…") is one text at run time.
                val source = file.readText(Charsets.UTF_8).replace(Regex(""""\s*\+\s*""""), "")
                call.findAll(source).map { it.groupValues[1] }
                    .map { text -> template.replace(text, "7") }
                    .filter { text -> text.any { it.isLetter() } }
                    .filterNot { text -> text in keys || shapes.any { it.matches(text) } }
                    .map { "${file.name}: $it" }
            }
            .toList()
        assertTrue(missing.isEmpty(), "no translation line for:\n" + missing.joinToString("\n"))
    }

    @Test
    fun `English passes straight through`() {
        StudioI18n.useForNow("en")
        assertEquals("Export .pfptheme…", tr("Export .pfptheme…"))
    }

    @Test
    fun `a plain line is translated`() {
        StudioI18n.useForNow("de")
        assertEquals(lines("de").getValue("Reset all"), tr("Reset all"))
    }

    @Test
    fun `a line with a gap keeps the piece, and translates it when it is a known phrase`() {
        StudioI18n.useForNow("de")
        val german = lines("de")
        assertEquals(german.getValue("Up to {0}").replace("{0}", "5.0 s"), tr("Up to 5.0 s"))
        assertEquals(
            german.getValue("Open: {0} ▾").replace("{0}", german.getValue("Icons")),
            tr("Open: Icons ▾"),
        )
    }

    @Test
    fun `gaps may change order`() {
        StudioI18n.useForNow("ja")
        assertEquals(lines("ja").getValue("Showing {0} of {1}").replace("{0}", "12").replace("{1}", "88"), tr("Showing 12 of 88"))
    }

    @Test
    fun `each line of a multi-line text is translated on its own`() {
        StudioI18n.useForNow("fr")
        val french = lines("fr")
        assertEquals("${french.getValue("Reset all")}\nmy-theme.pfptheme", tr("Reset all\nmy-theme.pfptheme"))
    }

    @Test
    fun `text with no line stays as written`() {
        StudioI18n.useForNow("ko")
        assertEquals("my-theme.pfptheme", tr("my-theme.pfptheme"))
    }

    @Test
    fun `the computer's language picks the closest shipped one`() {
        assertEquals("pt-PT", StudioI18n.tagFor(Locale.forLanguageTag("pt-PT")))
        assertEquals("pt-BR", StudioI18n.tagFor(Locale.forLanguageTag("pt")))
        assertEquals("zh-TW", StudioI18n.tagFor(Locale.forLanguageTag("zh-HK")))
        assertEquals("zh-CN", StudioI18n.tagFor(Locale.forLanguageTag("zh")))
        assertEquals("de", StudioI18n.tagFor(Locale.forLanguageTag("de-AT")))
        assertEquals("en", StudioI18n.tagFor(Locale.forLanguageTag("fi")))
    }
}
