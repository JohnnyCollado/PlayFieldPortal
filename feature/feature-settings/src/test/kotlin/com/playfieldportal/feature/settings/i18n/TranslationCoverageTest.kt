package com.playfieldportal.feature.settings.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every language the app ships must carry every translatable string of every module, and nothing else.
 *
 * Android falls back to English silently when a translation is missing, so a forgotten string never
 * fails a build — it shows up as one English row in a German menu. This reads the resource files of
 * every module directly (no Android runtime) and fails on the gaps a translator or a merge can leave:
 * a missing or stray string or plural, a lost `%1$s` placeholder, an empty value, or an apostrophe
 * the resource compiler would reject.
 *
 * It lives in this module only because a test needs a home; it checks the whole repository.
 */
class TranslationCoverageTest {

    /** Tests run with the module folder as working directory; the repository is two levels up. */
    private val repo = File("../..").canonicalFile

    private val resDirs: List<File> by lazy {
        repo.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" && it.name != "node_modules" && it.name != ".claude" }
            .filter { it.isDirectory && it.name == "res" && it.parentFile?.name == "main" && File(it, "values").isDirectory }
            .toList()
    }

    @Test
    fun `the scan finds the modules`() {
        assertTrue("found only ${resDirs.map { it.path }}", resDirs.size >= 3)
    }

    @Test
    fun `every module with English text has every target language`() {
        val gaps = resDirs.filter { english(it).isNotEmpty() }.flatMap { res ->
            TARGET_LOCALES.filter { loc -> !File(res, "values-$loc").listFiles().orEmpty().any { isStringsFile(it) } }
                .map { "${rel(res)}: values-$it" }
        }
        assertEquals("Missing language folders", emptyList<String>(), gaps)
    }

    @Test
    fun `no language folder exists outside the target list`() {
        val stray = resDirs.flatMap { res ->
            res.listFiles().orEmpty()
                .filter { it.isDirectory && it.name.startsWith("values-") && it.listFiles().orEmpty().any(::isStringsFile) }
                .map { it.name.removePrefix("values-") }
                .filter { it !in TARGET_LOCALES && !it.startsWith("night") && !it.startsWith("v") }
                .map { "${rel(res)}: $it" }
        }
        assertEquals(emptyList<String>(), stray)
    }

    @Test
    fun `every language carries exactly the translatable entries`() {
        val problems = mutableListOf<String>()
        forEachTranslation { where, base, translated ->
            (base.keys - translated.keys).forEach { problems += "$where: missing $it" }
            (translated.keys - base.keys).forEach { problems += "$where: not in English: $it" }
        }
        assertEquals(problems.take(50).joinToString("\n"), 0, problems.size)
    }

    @Test
    fun `translations keep the placeholders of the English text`() {
        val problems = mutableListOf<String>()
        forEachTranslation { where, base, translated ->
            val locale = where.substringAfterLast("values-")
            for ((key, english) in base) {
                val forms = translated[key] ?: continue
                // A plural is checked against its English "other" form, which carries every placeholder.
                val want = placeholders(english["other"] ?: english.values.first())
                forms.forEach { (q, text) ->
                    val got = placeholders(text)
                    val mayOmit = q != "" && fixedAmount(locale, q) && want.containsAll(got)
                    if (got != want && !mayOmit) problems += "$where/$key[$q]: $got != $want"
                }
            }
        }
        assertEquals(problems.take(50).joinToString("\n"), 0, problems.size)
    }

    @Test
    fun `every plural has an other form in every language`() {
        val problems = mutableListOf<String>()
        forEachTranslation { where, base, translated ->
            translated.filter { (k, forms) -> base[k]?.keys != setOf("") && "other" !in forms }.keys
                .forEach { problems += "$where: $it" }
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }

    @Test
    fun `no value is blank or carries an unescaped apostrophe`() {
        val problems = mutableListOf<String>()
        for (res in resDirs) {
            val folders = listOf("values") + TARGET_LOCALES.map { "values-$it" }
            for (folder in folders) {
                for ((key, forms) in read(File(res, folder))) {
                    forms.forEach { (q, v) ->
                        if (v.isBlank()) problems += "${rel(res)}/$folder/$key[$q] is blank"
                        if (UNESCAPED_APOSTROPHE.containsMatchIn(v)) problems += "${rel(res)}/$folder/$key[$q]: $v"
                    }
                }
            }
        }
        assertEquals(problems.take(50).joinToString("\n"), 0, problems.size)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun forEachTranslation(check: (String, Map<String, Map<String, String>>, Map<String, Map<String, String>>) -> Unit) {
        for (res in resDirs) {
            val base = english(res)
            if (base.isEmpty()) continue
            for (loc in TARGET_LOCALES) check("${rel(res)}/values-$loc", base, read(File(res, "values-$loc")))
        }
    }

    private fun english(res: File) = read(File(res, "values"))

    private fun isStringsFile(f: File) = f.isFile && f.name.startsWith("strings") && f.name.endsWith(".xml")

    /**
     * Every translatable entry in a values folder: name to (quantity to text). A `<string>` has one
     * form keyed "", a `<plurals>` one per quantity.
     */
    private fun read(dir: File): Map<String, Map<String, String>> {
        val out = linkedMapOf<String, Map<String, String>>()
        dir.listFiles().orEmpty().filter(::isStringsFile).forEach { file ->
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            val strings = root.getElementsByTagName("string")
            for (i in 0 until strings.length) {
                val e = strings.item(i) as Element
                if (e.parentNode != root || e.getAttribute("translatable") == "false") continue
                out[e.getAttribute("name")] = mapOf("" to e.textContent)
            }
            val plurals = root.getElementsByTagName("plurals")
            for (i in 0 until plurals.length) {
                val e = plurals.item(i) as Element
                if (e.getAttribute("translatable") == "false") continue
                val items = e.getElementsByTagName("item")
                out[e.getAttribute("name")] = (0 until items.length).associate {
                    val item = items.item(it) as Element
                    item.getAttribute("quantity") to item.textContent
                }
            }
        }
        return out
    }

    private fun rel(f: File) = f.relativeTo(repo).path.replace('\\', '/')

    /**
     * Whether plural form [quantity] in [locale] always stands for one fixed amount, so its text may
     * spell the amount out ("one game", "two games") instead of printing the number. Arabic's zero,
     * one and two forms do. "one" does everywhere EXCEPT in languages where it also covers 21, 31, …
     * (Russian, Croatian, Bosnian, Serbian): there it must keep the number.
     */
    private fun fixedAmount(locale: String, quantity: String): Boolean = when {
        locale == "ar" -> quantity in setOf("zero", "one", "two")
        quantity == "one" -> locale !in setOf("ru", "uk", "hr", "bs", "sr")
        else -> false
    }

    private fun placeholders(text: String): List<String> =
        PLACEHOLDER.findAll(text).map { it.value }.sorted().toList()

    companion object {
        /**
         * The ES-DE language list, as Android resource folder suffixes. English is the unqualified
         * `values` folder. `en-rGB` is absent on purpose: it only needs a folder once a string
         * actually differs from the base English.
         */
        val TARGET_LOCALES = listOf(
            "ar", "bs", "ca", "cs", "de", "es", "fr", "hr", "it", "ja", "ko", "nl", "pl",
            "pt-rBR", "pt-rPT", "ro", "ru", "sr", "sv", "vi", "zh-rCN", "zh-rTW",
        )

        private val PLACEHOLDER = Regex("""%(\d+\$)?[sdf]|%(\d+\$)?\.\d+f""")
        private val UNESCAPED_APOSTROPHE = Regex("""(?<!\\)'""")
    }
}
