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
 * Slot IDs: 0 = icon atlas + preview, 1 = wallpaper (-> 24-bit BMP), 2/3 = wave
 * graphics (-> GIM), 4 = color/config. Only the wallpaper is extracted here — the
 * import pipeline needs wallpaper + name + firmware; GIM icon decoding is out of scope
 * (we render our own icons; see docs/ptf-import-plan.md).
 *
 * Every slot payload starts with a 32-byte header (verified across official themes
 * spanning firmware 3.70–5.00):
 *
 * ```
 * +0   u32  sequence/index
 * +4   u16  resource type        (4 = wallpaper, 5 = other resources)
 * +6   u16  compression method   (1 = LZR, 2 = zlib)
 * +8   u32  compressed size
 * +12  u32  uncompressed size    (wallpaper: 480x272 24-bit BMP, ~391734 bytes)
 * +16  16 zero bytes
 * +32  compressed data
 * ```
 *
 * Firmware 3.70-era themes compress with LZR (method 1, decoded by [Lzr]), 3.80+ with
 * zlib (method 2). Any other method reports
 * [PtfTheme.wallpaperStatus] = [WallpaperStatus.UNSUPPORTED_COMPRESSION].
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

    /** Why [PtfTheme.wallpaper] is (or isn't) populated — lets callers explain failures. */
    enum class WallpaperStatus {
        DECODED,

        /** The theme has no wallpaper slot at all (some themes only restyle icons). */
        MISSING,

        /** The payload header declares a compression method we don't know (not LZR/zlib). */
        UNSUPPORTED_COMPRESSION,

        /** A wallpaper slot exists but its data would not decompress/decode. */
        CORRUPT,
    }

    data class Slot(val id: Int, val subtype: Int, val size: Int, val dataOffset: Int)

    data class PtfTheme(
        val name: String,
        val firmware: String,
        val slots: List<Slot>,
        /** Decoded wallpaper, when slot 1 held a decompressible 24-bit BMP. */
        val wallpaper: BmpImage?,
        val wallpaperStatus: WallpaperStatus,
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

        val wallpaperSlot = slots.firstOrNull { it.id == WALLPAPER_SLOT_ID }
        val (wallpaper, status) = when {
            wallpaperSlot == null -> null to WallpaperStatus.MISSING
            else -> extractWallpaper(bytes, wallpaperSlot)
        }

        return PtfTheme(
            name = name,
            firmware = firmware,
            slots = slots,
            wallpaper = wallpaper,
            wallpaperStatus = status,
        )
    }

    // Payload header layout (see class KDoc).
    private const val PAYLOAD_HEADER_SIZE = 32
    private const val RESOURCE_TYPE_WALLPAPER = 4
    private const val COMPRESSION_LZR = 1
    private const val COMPRESSION_ZLIB = 2

    private fun extractWallpaper(bytes: ByteArray, slot: Slot): Pair<BmpImage?, WallpaperStatus> {
        val start = slot.dataOffset
        val end = (slot.dataOffset.toLong() + slot.size).coerceAtMost(bytes.size.toLong()).toInt()
        if (start !in 0 until end) return null to WallpaperStatus.CORRUPT

        // Preferred path: the 32-byte payload header tells us the compression method and
        // exactly where/how much to inflate — no scanning, and sizes double as sanity checks.
        if (end - start >= PAYLOAD_HEADER_SIZE) {
            val type = bytes.u16(start + 4)
            val method = bytes.u16(start + 6)
            val compressedSize = bytes.u32(start + 8)
            val uncompressedSize = bytes.u32(start + 12)
            val headerPlausible = type == RESOURCE_TYPE_WALLPAPER &&
                compressedSize in 1..(end - start - PAYLOAD_HEADER_SIZE) &&
                uncompressedSize in 1..MAX_INFLATED_BYTES
            if (headerPlausible) {
                when (method) {
                    COMPRESSION_LZR -> {
                        val decompressed = Lzr.decompress(
                            input = bytes,
                            offset = start + PAYLOAD_HEADER_SIZE,
                            length = compressedSize,
                            maxOutput = uncompressedSize,
                        )
                        val bmp = decompressed?.let(Bmp::decode)
                        return if (bmp != null) bmp to WallpaperStatus.DECODED
                        else null to WallpaperStatus.CORRUPT
                    }
                    COMPRESSION_ZLIB -> {
                        val inflated = inflate(bytes, start + PAYLOAD_HEADER_SIZE, compressedSize)
                        val bmp = inflated?.let(Bmp::decode)
                        if (bmp != null) return bmp to WallpaperStatus.DECODED
                        // Header lied or stream is damaged — fall through to the scan.
                    }
                    else -> return null to WallpaperStatus.UNSUPPORTED_COMPRESSION
                }
            }
        }

        // Fallback for payloads without a recognizable header: scan for a zlib stream.
        val zlibStart = findZlibHeader(bytes, start, end)
            ?: return null to WallpaperStatus.CORRUPT
        val inflated = inflate(bytes, zlibStart, end - zlibStart)
            ?: return null to WallpaperStatus.CORRUPT
        val bmp = Bmp.decode(inflated) ?: return null to WallpaperStatus.CORRUPT
        return bmp to WallpaperStatus.DECODED
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
    internal const val MAX_INFLATED_BYTES = 32 * 1024 * 1024

    internal fun inflate(bytes: ByteArray, offset: Int, length: Int): ByteArray? {
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
