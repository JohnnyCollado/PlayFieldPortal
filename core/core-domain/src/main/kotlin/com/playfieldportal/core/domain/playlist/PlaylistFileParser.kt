package com.playfieldportal.core.domain.playlist

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class PlaylistFormat { M3U, PLS, XSPF }

/**
 * One line of a playlist file. [location] is exactly what the file said — a Windows path, an
 * Android path, a `file:` URI or a web link — because turning it into something comparable is the
 * matcher's job. The hints come from EXTINF / PLS / XSPF metadata and are all optional.
 */
data class PlaylistEntry(
    val location: String,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long? = null,
)

/**
 * What a playlist file held. [name] is the name the file gave itself (null when it gave none).
 * [dropped] counts entries cut by the entry cap. [rejected] is set when the file was refused
 * outright (too big); the entries are then empty.
 */
data class ParsedPlaylist(
    val format: PlaylistFormat,
    val name: String?,
    val entries: List<PlaylistEntry>,
    val dropped: Int = 0,
    val rejected: String? = null,
)

/**
 * Reads the bytes of a playlist file. Never throws: malformed input yields whatever was read before
 * the problem. Pure Kotlin so it runs in JVM tests and behaves the same on the device.
 */
object PlaylistFileParser {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_ENTRIES = 10_000

    // Old Winamp-era .m3u exports are Windows-1252, not UTF-8.
    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    fun parse(fileName: String, bytes: ByteArray): ParsedPlaylist {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val declared = when (extension) {
            "m3u", "m3u8" -> PlaylistFormat.M3U
            "pls" -> PlaylistFormat.PLS
            "xspf" -> PlaylistFormat.XSPF
            else -> null
        }
        if (bytes.size > MAX_BYTES) {
            return ParsedPlaylist(
                format = declared ?: PlaylistFormat.M3U,
                name = null,
                entries = emptyList(),
                rejected = "File is larger than ${MAX_BYTES / (1024 * 1024)} MiB",
            )
        }

        // Only .m3u (and unknown extensions, which are read as M3U) may fall back to Windows-1252.
        val legacyFallback = extension == "m3u" || declared == null
        val text = decode(bytes, legacyFallback)
        val format = declared ?: sniff(text)
        return when (format) {
            PlaylistFormat.M3U -> parseM3u(text)
            PlaylistFormat.PLS -> parsePls(text)
            PlaylistFormat.XSPF -> parseXspf(text)
        }
    }

    // ── Decoding ──

    private fun decode(bytes: ByteArray, legacyFallback: Boolean): String {
        fun b(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xFF)
        return when {
            b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            b(0) == 0xFF && b(1) == 0xFE -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            b(0) == 0xFE && b(1) == 0xFF -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            legacyFallback -> decodeStrictUtf8(bytes) ?: String(bytes, WINDOWS_1252)
            else -> String(bytes, Charsets.UTF_8)
        }
    }

    private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun sniff(text: String): PlaylistFormat {
        val head = text.trimStart('﻿', ' ', '\t', '\r', '\n')
        return when {
            head.startsWith("[playlist]", ignoreCase = true) -> PlaylistFormat.PLS
            head.startsWith("<?xml", ignoreCase = true) ||
                head.startsWith("<playlist", ignoreCase = true) -> PlaylistFormat.XSPF
            else -> PlaylistFormat.M3U
        }
    }

    // ── M3U ──

    private fun parseM3u(text: String): ParsedPlaylist {
        var name: String? = null
        val entries = ArrayList<PlaylistEntry>()
        var dropped = 0
        var pending: PlaylistEntry? = null // hints from the last EXTINF, waiting for its path line

        for (raw in text.split("\r\n", "\n", "\r")) {
            val line = raw.trim().trimStart('﻿').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                when {
                    line.startsWith(EXTINF, ignoreCase = true) ->
                        pending = parseExtInf(line.substring(EXTINF.length))
                    line.startsWith(PLAYLIST, ignoreCase = true) && name == null ->
                        name = line.substring(PLAYLIST.length).trim().ifEmpty { null }
                }
                continue
            }
            if (entries.size >= MAX_ENTRIES) {
                dropped++
            } else {
                entries += (pending ?: PlaylistEntry("")).copy(location = line)
            }
            pending = null
        }
        return ParsedPlaylist(PlaylistFormat.M3U, name, entries, dropped)
    }

    /** `<secs>[ attrs],<Artist - Title>` — the title starts after the first comma outside quotes. */
    private fun parseExtInf(body: String): PlaylistEntry {
        var inQuotes = false
        var comma = -1
        for (i in body.indices) {
            val c = body[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) { comma = i; break }
        }
        val head = if (comma >= 0) body.substring(0, comma) else body
        val display = if (comma >= 0) body.substring(comma + 1).trim() else ""

        val seconds = head.trim().takeWhile { !it.isWhitespace() }.toDoubleOrNull()
        val durationMs = seconds?.takeIf { it > 0 && it.isFinite() }?.let { (it * 1000).toLong() }

        return splitDisplay(display).copy(durationMs = durationMs)
    }

    /** `Artist - Title` (hyphen or en dash) → artist and title; anything else is a title only. */
    private fun splitDisplay(display: String): PlaylistEntry {
        val split = ARTIST_SEPARATORS.map { display.indexOf(it) }.filter { it > 0 }.minOrNull()
        val artist: String?
        val title: String?
        if (split != null) {
            artist = display.substring(0, split).trim().ifEmpty { null }
            title = display.substring(split + 3).trim().ifEmpty { null }
        } else {
            artist = null
            title = display.ifEmpty { null }
        }
        return PlaylistEntry(location = "", title = title, artist = artist)
    }

    // ── PLS ──

    private class PlsItem(var file: String? = null, var title: String? = null, var length: Long? = null)

    /** `FileN` / `TitleN` / `LengthN` lines, paired by N and returned in N order. */
    private fun parsePls(text: String): ParsedPlaylist {
        val items = java.util.TreeMap<Int, PlsItem>()
        for (raw in text.split("\r\n", "\n", "\r")) {
            val line = raw.trim().trimStart('﻿')
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val match = PLS_KEY.matchEntire(line.substring(0, eq).trim()) ?: continue
            val index = match.groupValues[2].toIntOrNull() ?: continue
            val value = line.substring(eq + 1).trim()
            val item = items.getOrPut(index) { PlsItem() }
            when (match.groupValues[1].lowercase()) {
                "file" -> item.file = value.ifEmpty { null }
                "title" -> item.title = value
                "length" -> item.length = value.toLongOrNull()
            }
        }
        val all = items.values.filter { it.file != null }
        val entries = all.take(MAX_ENTRIES).map { item ->
            val named = splitDisplay(item.title.orEmpty().trim())
            named.copy(
                location = item.file.orEmpty(),
                durationMs = item.length?.takeIf { it > 0 }?.let { it * 1000 },
            )
        }
        return ParsedPlaylist(PlaylistFormat.PLS, name = null, entries, dropped = all.size - entries.size)
    }

    // ── XSPF ──

    private class XspfTrack {
        var location: String? = null
        var creator: String? = null
        var title: String? = null
        var durationMs: Long? = null
    }

    /**
     * A small tag scanner, not an XML parser: `android.util.Xml` is missing on the JVM and a DTD-aware
     * parser would resolve external entities. DOCTYPE and processing instructions are skipped, only
     * the five predefined and numeric entities are decoded, and anything else stays as written.
     * Namespace prefixes are ignored. Truncated input keeps the tracks that were closed.
     */
    private fun parseXspf(text: String): ParsedPlaylist {
        var name: String? = null
        val entries = ArrayList<PlaylistEntry>()
        var dropped = 0
        val stack = ArrayList<String>()
        val content = StringBuilder()
        var track: XspfTrack? = null

        fun open(tag: String) {
            stack += tag
            content.setLength(0)
            if (tag == "track") track = XspfTrack()
        }

        fun close(tag: String) {
            val value = content.toString().trim().ifEmpty { null }
            val parent = stack.getOrNull(stack.size - 2)
            when {
                tag == "title" && parent == "playlist" -> if (name == null) name = value
                parent == "track" -> track?.let {
                    when (tag) {
                        "location" -> if (it.location == null) it.location = value
                        "creator" -> it.creator = value
                        "title" -> it.title = value
                        "duration" -> it.durationMs = value?.toLongOrNull()?.takeIf { ms -> ms > 0 }
                    }
                }
                tag == "track" -> {
                    track?.location?.let { location ->
                        if (entries.size >= MAX_ENTRIES) dropped++
                        else entries += PlaylistEntry(location, track?.title, track?.creator, track?.durationMs)
                    }
                    track = null
                }
            }
            content.setLength(0)
            val at = stack.lastIndexOf(tag)
            if (at >= 0) while (stack.size > at) stack.removeAt(stack.size - 1)
        }

        var i = 0
        while (i < text.length) {
            val lt = text.indexOf('<', i)
            if (lt < 0) {
                appendDecoded(content, text, i, text.length)
                break
            }
            appendDecoded(content, text, i, lt)
            when {
                text.startsWith("<!--", lt) -> i = skipPast(text, "-->", lt + 4)
                text.startsWith("<![CDATA[", lt) -> {
                    val end = text.indexOf("]]>", lt + 9)
                    content.append(text, lt + 9, if (end < 0) text.length else end)
                    i = if (end < 0) text.length else end + 3
                }
                text.startsWith("<?", lt) -> i = skipPast(text, "?>", lt + 2)
                text.startsWith("<!", lt) -> i = skipPast(text, ">", lt + 2)
                else -> {
                    val end = tagEnd(text, lt + 1)
                    if (end < 0) break
                    var body = text.substring(lt + 1, end).trim()
                    i = end + 1
                    val closing = body.startsWith("/")
                    val selfClosing = body.endsWith("/")
                    if (closing) body = body.substring(1)
                    if (selfClosing) body = body.dropLast(1)
                    val tag = body.trim().takeWhile { !it.isWhitespace() }.substringAfterLast(':').lowercase()
                    if (tag.isEmpty()) continue
                    if (closing) {
                        close(tag)
                    } else {
                        open(tag)
                        if (selfClosing) close(tag)
                    }
                }
            }
        }
        return ParsedPlaylist(PlaylistFormat.XSPF, name, entries, dropped)
    }

    private fun skipPast(text: String, marker: String, from: Int): Int {
        val at = text.indexOf(marker, from)
        return if (at < 0) text.length else at + marker.length
    }

    /** Index of the `>` that ends a tag, ignoring any inside a quoted attribute value; -1 if none. */
    private fun tagEnd(text: String, from: Int): Int {
        var quote = 0.toChar()
        for (i in from until text.length) {
            val c = text[i]
            when {
                quote != 0.toChar() -> if (c == quote) quote = 0.toChar()
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
        }
        return -1
    }

    private fun appendDecoded(out: StringBuilder, text: String, from: Int, to: Int) {
        var i = from
        while (i < to) {
            val c = text[i]
            if (c == '&') {
                val semi = text.indexOf(';', i + 1)
                if (semi in (i + 2)..minOf(i + 10, to - 1)) {
                    val decoded = decodeEntity(text.substring(i + 1, semi))
                    if (decoded != null) {
                        out.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
    }

    private fun decodeEntity(name: String): String? = when {
        name == "amp" -> "&"
        name == "lt" -> "<"
        name == "gt" -> ">"
        name == "quot" -> "\""
        name == "apos" -> "'"
        name.startsWith("#x") || name.startsWith("#X") -> codePointString(name.substring(2).toIntOrNull(16))
        name.startsWith("#") -> codePointString(name.substring(1).toIntOrNull())
        else -> null
    }

    private fun codePointString(code: Int?): String? =
        if (code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF && code != 0) {
            String(Character.toChars(code))
        } else {
            null
        }

    private const val EXTINF = "#EXTINF:"
    private const val PLAYLIST = "#PLAYLIST:"
    private val ARTIST_SEPARATORS = listOf(" - ", " – ")
    private val PLS_KEY = Regex("(file|title|length)(\\d+)", RegexOption.IGNORE_CASE)
}
