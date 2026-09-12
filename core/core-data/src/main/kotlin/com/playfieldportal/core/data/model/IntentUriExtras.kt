package com.playfieldportal.core.data.model

import java.io.ByteArrayOutputStream

/**
 * The typed extras of an `Intent.toUri(URI_INTENT_SCHEME)` string, read as pure string work rather
 * than through `Intent.parseUri`, so migrations and pure logic can read a stored launch intent on
 * the JVM with no Android runtime.
 *
 * Extracted from `StorefrontIdentity` when C18 also needed Winlator's `shortcut_path`, so one parser
 * serves both.
 */
object IntentUriExtras {

    /**
     * Every typed extra, keyed by its typed prefix ("i.app_id", "S.game_source"). Segments are
     * ';'-separated and values are encoded by `Uri.encode`, so each value is decoded. Intent fields
     * such as `action=` or `component=` are not extras and are left out.
     */
    fun parse(intentUri: String): Map<String, String> {
        val body = intentUri.substringAfter("#Intent;", missingDelimiterValue = intentUri)
        return body.split(';')
            .mapNotNull { segment ->
                if (!segment.contains('=')) return@mapNotNull null
                val key = segment.substringBefore('=')
                // Only typed-extra keys ("i.", "S.", "B.", "l.", …) — never action=, component=, …
                if (key.length < 3 || key[1] != '.') return@mapNotNull null
                key to decode(segment.substringAfter('='))
            }
            .toMap()
    }

    /** The decoded value of the string extra [name] (`S.<name>`), or null when absent. */
    fun stringExtra(intentUri: String?, name: String): String? =
        intentUri?.takeIf { it.isNotBlank() }?.let { parse(it)["S.$name"] }

    /**
     * Undoes `Uri.encode`: `%XX` escapes are UTF-8 bytes, so a path such as `Pokémon` (`%C3%A9`)
     * decodes to one character, not two. Anything that is not a valid escape is kept as written.
     */
    private fun decode(raw: String): String {
        if ('%' !in raw) return raw
        val bytes = ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '%' && i + 2 < raw.length) {
                val high = Character.digit(raw[i + 1], 16)
                val low = Character.digit(raw[i + 2], 16)
                if (high >= 0 && low >= 0) {
                    bytes.write(high * 16 + low)
                    i += 3
                    continue
                }
            }
            // Copy up to the next '%' as it is: splitting a run character by character would break a
            // surrogate pair apart.
            val next = raw.indexOf('%', i + 1).let { if (it < 0) raw.length else it }
            bytes.write(raw.substring(i, next).toByteArray(Charsets.UTF_8))
            i = next
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
