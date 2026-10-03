package com.playfieldportal.core.domain.model.emulatorkb

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Outcome of [EmulatorKbDecoder.decode]. */
sealed interface EmulatorKbDecode {
    data class Decoded(val document: EmulatorKbDocument) : EmulatorKbDecode

    /** The whole file is unusable. [reason] is readable on its own. */
    data class Rejected(val reason: String) : EmulatorKbDecode
}

/**
 * Structural decode of a KB file (AD-2, AD-4). Unknown keys are refused, at the top level (the
 * document) and inside each emulator / platform item (that item only). Never throws.
 *
 * The caller compares `schemaVersion` and `minAppVersion` with the running app; this only reports them.
 */
object EmulatorKbDecoder {
    const val FORMAT = "pfp-emulator-kb"

    /** The schema version this app reads and writes; the one place it is spelled out. */
    const val SCHEMA_VERSION = 1
    const val MAX_CHARS = 1024 * 1024
    const val MAX_EMULATORS = 500
    const val MAX_PLATFORMS = 64

    // No legitimate field is near this long; the semantic caps in the validator are tighter.
    const val MAX_STRING_CHARS = 512

    // A real file nests about 7 deep. The document bound guards the parser; the item bound is per entry.
    const val MAX_DOCUMENT_DEPTH = 64
    const val MAX_ITEM_DEPTH = 32

    private val TOP_LEVEL_KEYS =
        setOf("format", "schemaVersion", "version", "label", "minAppVersion", "emulators", "platforms")

    // Strict: an unknown key throws.
    private val json = Json { ignoreUnknownKeys = false }

    fun decode(text: String): EmulatorKbDecode {
        if (text.length > MAX_CHARS) return EmulatorKbDecode.Rejected("is too large to be an emulator knowledge file")
        if (isNestedTooDeeply(text)) return EmulatorKbDecode.Rejected("is nested too deeply to be an emulator knowledge file")
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return EmulatorKbDecode.Rejected("is not a PlayFieldPortal emulator knowledge file")

        val unknown = root.keys.firstOrNull { it !in TOP_LEVEL_KEYS }
        if (unknown != null) return EmulatorKbDecode.Rejected("has an unknown key \"$unknown\"")
        if ((root["format"] as? JsonPrimitive)?.contentOrNull != FORMAT) {
            return EmulatorKbDecode.Rejected("has the wrong format (expected \"$FORMAT\")")
        }
        val schemaVersion = root.intField("schemaVersion")
            ?: return EmulatorKbDecode.Rejected("has no integer schemaVersion")
        val version = if (root["version"] == null) 0L
        else root.longField("version") ?: return EmulatorKbDecode.Rejected("has a non-integer version")
        val minAppVersion = if (root["minAppVersion"] == null) 0
        else root.intField("minAppVersion") ?: return EmulatorKbDecode.Rejected("has a non-integer minAppVersion")
        val label = when (val l = root["label"]) {
            null -> ""
            is JsonPrimitive -> l.takeIf { it.isString }?.content
            else -> null
        } ?: return EmulatorKbDecode.Rejected("has a non-text label")
        if (label.length > MAX_STRING_CHARS) return EmulatorKbDecode.Rejected("has a label that is too long")

        val emulators = root.items("emulators")
            ?: return EmulatorKbDecode.Rejected("has an \"emulators\" value that is not a list")
        val platforms = root.items("platforms")
            ?: return EmulatorKbDecode.Rejected("has a \"platforms\" value that is not a list")
        if (emulators.size > MAX_EMULATORS) {
            return EmulatorKbDecode.Rejected("lists too many emulators (${emulators.size}, at most $MAX_EMULATORS)")
        }
        if (platforms.size > MAX_PLATFORMS) {
            return EmulatorKbDecode.Rejected("lists too many platforms (${platforms.size}, at most $MAX_PLATFORMS)")
        }

        return EmulatorKbDecode.Decoded(
            EmulatorKbDocument(
                format = FORMAT,
                schemaVersion = schemaVersion,
                version = version,
                label = label,
                minAppVersion = minAppVersion,
                emulators = emulators.mapIndexed { i, el -> decodeItem(i, el, EmulatorKbEmulator.serializer()) },
                platforms = platforms.mapIndexed { i, el -> decodeItem(i, el, EmulatorKbPlatform.serializer()) },
            ),
        )
    }

    private fun <T> decodeItem(index: Int, element: JsonElement, serializer: KSerializer<T>): KbItem<T> {
        val id = ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.take(MAX_STRING_CHARS)
        structuralRefusal(element)?.let { return KbItem.Rejected(index, id, it) }
        return try {
            KbItem.Ok(json.decodeFromJsonElement(serializer, element))
        } catch (e: IllegalArgumentException) {
            // SerializationException: unknown key, missing field or wrong type. The first line of its
            // message names the key, e.g. "Encountered an unknown key 'x'".
            val reason = e.message?.lineSequence()?.firstOrNull()?.take(MAX_STRING_CHARS)
            KbItem.Rejected(index, id, reason ?: "could not be read")
        }
    }

    /** Why [element] cannot be read for a reason of its own (an over long string or too deep nesting), else null. */
    private fun structuralRefusal(element: JsonElement): String? {
        // Iterative: nesting is attacker controlled, so recursion here could overflow the stack.
        val pending = ArrayDeque<Pair<JsonElement, Int>>()
        pending.addLast(element to 1)
        while (pending.isNotEmpty()) {
            val (current, depth) = pending.removeLast()
            when (current) {
                is JsonPrimitive ->
                    if (current.isString && current.content.length > MAX_STRING_CHARS) return "has a value that is too long"
                is JsonArray -> {
                    if (depth > MAX_ITEM_DEPTH) return "is nested too deeply"
                    current.forEach { pending.addLast(it to depth + 1) }
                }
                is JsonObject -> {
                    if (depth > MAX_ITEM_DEPTH) return "is nested too deeply"
                    for ((k, v) in current) {
                        if (k.length > MAX_STRING_CHARS) return "has a value that is too long"
                        pending.addLast(v to depth + 1)
                    }
                }
                JsonNull -> Unit
            }
        }
        return null
    }

    /**
     * True when brackets nest deeper than [MAX_DOCUMENT_DEPTH]. Runs before parsing because the JSON
     * parser recurses per level and a hostile file would overflow the stack inside it.
     */
    private fun isNestedTooDeeply(text: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            when {
                escaped -> escaped = false
                inString -> if (c == '\\') escaped = true else if (c == '"') inString = false
                c == '"' -> inString = true
                c == '[' || c == '{' -> if (++depth > MAX_DOCUMENT_DEPTH) return true
                c == ']' || c == '}' -> depth--
            }
        }
        return false
    }

    private fun JsonObject.intField(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonObject.longField(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    /** The array under [key] (empty when absent), or null when the value is not an array. */
    private fun JsonObject.items(key: String): List<JsonElement>? = when (val v = this[key]) {
        null -> emptyList()
        is JsonArray -> v
        else -> null
    }
}
