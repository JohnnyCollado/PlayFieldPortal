package com.playfieldportal.core.domain.playlist

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale

/** A playlist entry's location after clean-up, ready for the matcher to compare. */
sealed interface PlaylistLocation {
    /** Nothing usable on the line. */
    data object Empty : PlaylistLocation

    /** A stream or remote address (http, rtsp, mms, ...) — never in the library. */
    data object WebLink : PlaylistLocation

    /**
     * A local path as lowercase, NFC-normalised segments with any drive / UNC prefix and `.` / `..`
     * resolved. [segments] is the path as written (percent-decoded for a `file:` URI).
     * [decodedSegments] is the percent-decoded variant of a plain path that held a real escape such
     * as `%20`, and null when decoding would change nothing.
     */
    data class Path(
        val segments: List<String>,
        val decodedSegments: List<String>? = null,
    ) : PlaylistLocation
}

/**
 * Turns a raw playlist location into a [PlaylistLocation]. Pure Kotlin and never throws. Percent
 * decoding is done by hand: `URLDecoder` would turn `+` into a space and throw on `%zz`.
 */
object PlaylistLocationNormalizer {

    // A scheme of two or more letters followed by "//". One letter is a Windows drive (`C:/x`).
    private val webScheme = Regex("^[A-Za-z][A-Za-z0-9+.\\-]+://")
    private val driveLetter = Regex("^[A-Za-z]:")

    fun normalize(raw: String): PlaylistLocation {
        val text = raw.trim()
        if (text.isEmpty()) return PlaylistLocation.Empty

        if (text.startsWith("file:", ignoreCase = true)) return fileUri(text.substring(5))

        if (webScheme.containsMatchIn(text)) return PlaylistLocation.WebLink

        val slashed = text.replace('\\', '/')
        val parts = slashed.split('/').filter { it.isNotEmpty() }
        val unc = slashed.startsWith("//")
        val body = if (unc) parts.drop(2) else parts

        val asWritten = clean(body) { it }
        val decoded = clean(body, ::percentDecode)
        return PlaylistLocation.Path(asWritten, decoded.takeIf { it != asWritten })
    }

    /** [rest] is everything after `file:`: `///x`, `//localhost/x`, `/C:/x` or `C:/x`. */
    private fun fileUri(rest: String): PlaylistLocation {
        val slashed = rest.replace('\\', '/')
        // `//authority/path`: the authority (empty, localhost or a host) is not part of the path.
        val path = if (slashed.startsWith("//")) {
            val end = slashed.indexOf('/', 2)
            if (end < 0) "" else slashed.substring(end)
        } else {
            slashed
        }
        val parts = path.split('/').filter { it.isNotEmpty() }
        return PlaylistLocation.Path(clean(parts, ::percentDecode))
    }

    /** Drops a drive, resolves `.` / `..` (a leading `..` is dropped), then NFC-folds and lowercases. */
    private fun clean(parts: List<String>, transform: (String) -> String): List<String> {
        val out = ArrayList<String>(parts.size)
        parts.forEachIndexed { index, part ->
            var segment = transform(part)
            if (index == 0 && driveLetter.containsMatchIn(segment)) segment = segment.substring(2)
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                else -> out.add(Normalizer.normalize(segment, Normalizer.Form.NFC).lowercase(Locale.ROOT))
            }
        }
        return out
    }

    /**
     * UTF-8 percent-decoding that leaves `+` alone. A malformed escape (`%zz`, a lone `%`) stays
     * literal, and if the decoded bytes are not valid UTF-8 the text is kept as written.
     */
    private fun percentDecode(text: String): String {
        if ('%' !in text) return text
        val bytes = java.io.ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val hi = if (c == '%' && i + 2 < text.length) hexValue(text[i + 1]) else -1
            val lo = if (hi >= 0) hexValue(text[i + 2]) else -1
            if (hi >= 0 && lo >= 0) {
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                val end = if (Character.isHighSurrogate(c) && i + 1 < text.length) i + 2 else i + 1
                bytes.write(text.substring(i, end).toByteArray(Charsets.UTF_8))
                i = end
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            text
        }
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
