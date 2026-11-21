package com.playfieldportal.core.domain.text

import com.playfieldportal.core.domain.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Resolves this module's string resources to their English text on the plain JVM, with no
 * Robolectric: ids come from the generated [R] class by field name, values from
 * `src/main/res/values/strings_domain.xml`. Lets a test assert the English a [UiText] produces.
 */
object EnglishStrings {

    private val ids: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    private val values: Map<String, String> by lazy {
        val file = listOf("src/main/res/values/strings_domain.xml", "core/core-domain/src/main/res/values/strings_domain.xml")
            .map(::File).first { it.isFile }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to unescape(node.textContent)
        }
    }

    // aapt2's escapes: \' \" \n \@ \? — the XML entities were already decoded by the parser.
    private fun unescape(raw: String): String =
        Regex("""\\(.)""").replace(raw) { if (it.groupValues[1] == "n") "\n" else it.groupValues[1] }

    /** The English text of string resource [id]. */
    fun string(id: Int): String {
        val name = ids[id] ?: error("No string resource with id $id in core-domain's R")
        return values[name] ?: error("string '$name' is missing from strings_domain.xml")
    }

    /** The resource names declared in strings_domain.xml. */
    val declaredNames: Set<String> get() = values.keys

    /** The resource names in the generated R class. */
    val compiledNames: Set<String> get() = ids.values.toSet()

    /** [text] resolved to English, with the same rules as [UiText.resolve]. */
    fun of(text: UiText): String = when (text) {
        is UiText.Res ->
            if (text.args.isEmpty()) string(text.id)
            else String.format(string(text.id), *text.args.map { if (it is UiText) of(it) else it }.toTypedArray())
        is UiText.Raw -> text.value
        is UiText.Joined -> text.parts.joinToString(text.separator) { of(it) }
        is UiText.Plural -> error("core-domain has no plurals yet")
    }
}
