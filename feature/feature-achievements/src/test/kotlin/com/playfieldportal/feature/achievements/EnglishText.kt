package com.playfieldportal.feature.achievements

import android.content.Context
import android.content.res.Resources
import io.mockk.every
import io.mockk.mockk
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A mock [Context] that answers `getString` / `getQuantityString` with the English text from this
 * module's own `strings_achievements.xml`, so a plain JVM test can keep asserting on English
 * without Android resources (this module's unit tests do not merge them).
 *
 * Resource names are found by reflecting over the generated `R` class, so a test names the string
 * it expects through `R.string.x` exactly as production code does and the file stays the one place
 * the wording lives.
 */
object EnglishText {

    private val file = File("src/main/res/values/strings_achievements.xml")

    private val strings = HashMap<String, String>()
    private val plurals = HashMap<String, Map<String, String>>()

    private val stringNames: Map<Int, String> =
        R.string::class.java.fields.filter { it.type == Int::class.javaPrimitiveType }.associate { it.getInt(null) to it.name }
    private val pluralNames: Map<Int, String> =
        R.plurals::class.java.fields.filter { it.type == Int::class.javaPrimitiveType }.associate { it.getInt(null) to it.name }

    init {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val stringNodes = doc.getElementsByTagName("string")
        for (i in 0 until stringNodes.length) {
            val node = stringNodes.item(i)
            strings[node.attributes.getNamedItem("name").nodeValue] = unescape(node.textContent)
        }
        val pluralNodes = doc.getElementsByTagName("plurals")
        for (i in 0 until pluralNodes.length) {
            val node = pluralNodes.item(i)
            val items = HashMap<String, String>()
            val children = node.childNodes
            for (j in 0 until children.length) {
                val item = children.item(j)
                if (item.nodeName == "item") items[item.attributes.getNamedItem("quantity").nodeValue] = unescape(item.textContent)
            }
            plurals[node.attributes.getNamedItem("name").nodeValue] = items
        }
    }

    // aapt2's escapes: a backslash makes the next character literal, \n is a newline.
    private fun unescape(raw: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                i++
                out.append(if (raw[i] == 'n') '\n' else raw[i])
            } else {
                out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun string(id: Int, args: List<Any?>): String {
        val name = stringNames[id] ?: error("No string with id $id in R.string")
        val pattern = strings[name] ?: error("string $name is missing from strings_achievements.xml")
        return if (args.isEmpty()) pattern else pattern.format(*args.toTypedArray())
    }

    private fun quantity(id: Int, quantity: Int, args: List<Any?>): String {
        val name = pluralNames[id] ?: error("No plurals with id $id in R.plurals")
        val items = plurals[name] ?: error("plurals $name is missing from strings_achievements.xml")
        val pattern = items[if (quantity == 1) "one" else "other"] ?: error("plurals $name has no entry for $quantity")
        return pattern.format(*args.toTypedArray())
    }

    private fun flatten(args: List<Any?>): List<Any?> = args.flatMap { if (it is Array<*>) it.toList() else listOf(it) }

    fun context(): Context {
        val resources = mockk<Resources>(relaxed = true)
        every { resources.getString(any<Int>()) } answers { string(firstArg(), emptyList()) }
        every { resources.getString(any<Int>(), *anyVararg()) } answers { string(firstArg(), flatten(args.drop(1))) }
        every { resources.getQuantityString(any<Int>(), any<Int>(), *anyVararg()) } answers {
            quantity(firstArg(), secondArg(), flatten(args.drop(2)))
        }
        val context = mockk<Context>(relaxed = true)
        every { context.resources } returns resources
        every { context.getString(any<Int>()) } answers { string(firstArg(), emptyList()) }
        every { context.getString(any<Int>(), *anyVararg()) } answers { string(firstArg(), flatten(args.drop(1))) }
        return context
    }
}
