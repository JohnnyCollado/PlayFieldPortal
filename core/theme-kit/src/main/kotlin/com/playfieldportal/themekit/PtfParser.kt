package com.playfieldportal.themekit

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Parser for official Sony PSP theme files (`.ptf`).
 *
 * Format (verified against Sony's Custom Theme Creation Guidelines v5.00 and the official
 * example themes `classypink.ptf` / `cookies.ptf` — see docs/official-ptf-template.md):
 *
 * ```
 * 0x000  magic "\0PTF"
 * 0x008  display name (16 bytes, NUL-padded; longer titles are truncated by the format)
 * 0x0B8  target firmware string, e.g. "5.00" (8 bytes)
 * 0x100  resource table: up to [MAX_SLOTS] uint32-LE pointers, zero-terminated
 *        each pointer -> descriptor [ id:u16 | subtype:u16 | size:u32 | dataOffset:u32 ]
 * ```
 *
 * Slot IDs: 0 = icon atlas + preview, 1 = wallpaper (zlib -> 24-bit BMP), 2/3 = wave
 * graphics (zlib -> GIM), 4 = color/config. Only the wallpaper is extracted here — the
 * import pipeline needs wallpaper + name + firmware; GIM icon decoding is out of scope
 * (we render our own icons; see docs/ptf-import-plan.md).
 */
object PtfParser {

    private val MAGIC = byteArrayOf(0x00, 'P'.code.toByte(), 'T'.code.toByte(), 'F'.code.toByte())
    private const val NAME_OFFSET = 0x08
    private const val NAME_LENGTH = 16
    private const val FIRMWARE_OFFSET = 0xB8
    private const val FIRMWARE_LENGTH = 8
    private const val TABLE_OFFSET = 0x100
    private const val MAX_SLOTS = 16
    private const val WALLPAPER_SLOT_ID = 1

    /** What a `\0PTF`-magic file actually is. CXMB `.ctf` files reuse the same magic. */
    enum class Kind { OFFICIAL_PTF, CXMB, NOT_PTF }

    data class Slot(val id: Int, val subtype: Int, val size: Int, val dataOffset: Int)

    data class PtfTheme(
        val name: String,
        val firmware: String,
        val slots: List<Slot>,
        /** Decoded wallpaper, when slot 1 held a valid zlib-compressed 24-bit BMP. */
        val wallpaper: BmpImage?,
    )

    /**
     * Distinguishes an official theme from a CXMB flash0 replacement before parsing.
     * CXMB files embed flash0 resource paths; official PTFs never contain them.
     */
    fun detect(bytes: ByteArray): Kind {
        if (bytes.size < TABLE_OFFSET + 4 || !bytes.startsWith(MAGIC)) return Kind.NOT_PTF
        return if (bytes.containsAscii("/vsh/resource/")) Kind.CXMB else Kind.OFFICIAL_PTF
    }

    /**
     * Parses an official PTF. Returns null when [bytes] is not an official theme
     * (wrong magic, truncated, or a CXMB file) — callers use [detect] for a reason.
     */
    fun parse(bytes: ByteArray): PtfTheme? {
        if (detect(bytes) != Kind.OFFICIAL_PTF) return null

        val name = bytes.asciiString(NAME_OFFSET, NAME_LENGTH)
        val firmware = bytes.asciiString(FIRMWARE_OFFSET, FIRMWARE_LENGTH)

        val slots = buildList {
            for (i in 0 until MAX_SLOTS) {
                val ptrOffset = TABLE_OFFSET + i * 4
                if (ptrOffset + 4 > bytes.size) break
                val ptr = bytes.u32(ptrOffset)
                if (ptr == 0) break
                if (ptr + 12 > bytes.size) break
                add(
                    Slot(
                        id = bytes.u16(ptr),
                        subtype = bytes.u16(ptr + 2),
                        size = bytes.u32(ptr + 4),
                        dataOffset = bytes.u32(ptr + 8),
                    ),
                )
            }
        }

        val wallpaper = slots.firstOrNull { it.id == WALLPAPER_SLOT_ID }
            ?.let { extractWallpaper(bytes, it) }

        return PtfTheme(name = name, firmware = firmware, slots = slots, wallpaper = wallpaper)
    }

    /** Slot 1 payload = header bytes + a zlib stream that inflates to a 24-bit BMP. */
    private fun extractWallpaper(bytes: ByteArray, slot: Slot): BmpImage? {
        val start = slot.dataOffset
        val end = (slot.dataOffset.toLong() + slot.size).coerceAtMost(bytes.size.toLong()).toInt()
        if (start !in 0 until end) return null

        val zlibStart = findZlibHeader(bytes, start, end) ?: return null
        val inflated = inflate(bytes, zlibStart, end - zlibStart) ?: return null
        return Bmp.decode(inflated)
    }

    /** First plausible zlib header (0x78 followed by a valid FCHECK byte) in [from, until). */
    private fun findZlibHeader(bytes: ByteArray, from: Int, until: Int): Int? {
        for (i in from until until - 1) {
            if (bytes[i] == 0x78.toByte()) {
                val cmf = bytes[i].toInt() and 0xFF
                val flg = bytes[i + 1].toInt() and 0xFF
                if ((cmf * 256 + flg) % 31 == 0) return i
            }
        }
        return null
    }

    // Real PTF wallpapers inflate to ~390KB (480x272 24-bit BMP); this cap only exists to
    // stop decompression bombs in attacker-crafted files from OOMing the process.
    private const val MAX_INFLATED_BYTES = 32 * 1024 * 1024

    private fun inflate(bytes: ByteArray, offset: Int, length: Int): ByteArray? {
        val inflater = Inflater()
        inflater.setInput(bytes, offset, length)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n > 0) {
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_INFLATED_BYTES) return null // decompression bomb
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    return null // truncated or preset-dictionary stream — not a theme wallpaper
                }
            }
        } catch (_: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    // ── byte helpers ─────────────────────────────────────────────────────────

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.asciiString(offset: Int, maxLength: Int): String {
        if (offset >= size) return ""
        val end = (offset + maxLength).coerceAtMost(size)
        val nul = (offset until end).firstOrNull { this[it] == 0.toByte() } ?: end
        return String(this, offset, nul - offset, Charsets.ISO_8859_1).trim()
    }

    private fun ByteArray.containsAscii(needle: String): Boolean {
        val n = needle.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..size - n.size) {
            for (j in n.indices) if (this[i + j] != n[j]) continue@outer
            return true
        }
        return false
    }
}
