package com.playfieldportal.themekit

/**
 * Decoder for Sony's GIM texture container (`MIG.00.1PSP` magic) — the format PSP themes
 * store icons, category ribbons, and previews in.
 *
 * Supported: the chunked layout (root/picture containers, palette + image blocks),
 * direct-color formats RGBA5650/5551/4444/8888, indexed formats (4- and 8-bit) through a
 * palette block, and the PSP's swizzled pixel order (16-byte × 8-row blocks, with row
 * pitch padded to 16 bytes and swizzled height to 8 rows). The first image block wins;
 * mipmap tails are ignored. Pure Kotlin, bounds-checked, null on any malformation —
 * same contract as [Bmp].
 */
object Gim {

    private val MAGIC = "MIG.00.1PSP".toByteArray(Charsets.US_ASCII)

    private const val CHUNK_ROOT = 0x02
    private const val CHUNK_PICTURE = 0x03
    private const val CHUNK_IMAGE = 0x04
    private const val CHUNK_PALETTE = 0x05

    private const val FORMAT_RGBA5650 = 0
    private const val FORMAT_RGBA5551 = 1
    private const val FORMAT_RGBA4444 = 2
    private const val FORMAT_RGBA8888 = 3
    private const val FORMAT_INDEX4 = 4
    private const val FORMAT_INDEX8 = 5

    private const val MAX_DIMENSION = 8192 // matches Bmp.kt: caps allocation from hostile headers
    private const val MAX_PALETTE_ENTRIES = 65536

    /** True when [bytes] carries the GIM magic (cheap sniff for classifying resources). */
    fun isGim(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.size) return false
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return false
        return true
    }

    /** Decodes the first image in the container; null when [bytes] is not a valid GIM. */
    fun decode(bytes: ByteArray): BmpImage? {
        if (!isGim(bytes) || bytes.size < 32) return null

        var palette: IntArray? = null
        var offset = 16
        while (offset + 16 <= bytes.size) {
            val id = bytes.u16(offset)
            val size = bytes.i32(offset + 4)
            when (id) {
                CHUNK_ROOT, CHUNK_PICTURE -> {
                    offset += 16 // containers: descend into children
                    continue
                }
                CHUNK_PALETTE -> palette = decodePalette(bytes, offset, size) ?: return null
                CHUNK_IMAGE -> return decodeImage(bytes, offset, size, palette)
            }
            if (size < 16) return null // malformed chunk would loop forever
            offset += size
        }
        return null // no image block
    }

    // Block data header (at chunk + 16): u16 headerSize, u16 reserved, u16 format,
    // u16 pixelOrder, u16 width, u16 height, u16 bpp, u16 pitchAlign, u16 heightAlign,
    // ...; u16 at +28 = offset from chunk+16 to the pixel data (64 in every theme GIM).
    private const val BLOCK_HEADER = 16

    private fun dataStart(bytes: ByteArray, chunk: Int): Int {
        val declared = bytes.u16(chunk + BLOCK_HEADER + 28)
        return chunk + BLOCK_HEADER + if (declared in 16..1024) declared else 64
    }

    private fun decodePalette(bytes: ByteArray, chunk: Int, size: Int): IntArray? {
        if (chunk + BLOCK_HEADER + 32 > bytes.size) return null
        val format = bytes.u16(chunk + BLOCK_HEADER + 4)
        val entries = bytes.u16(chunk + BLOCK_HEADER + 8)
        if (format !in FORMAT_RGBA5650..FORMAT_RGBA8888 || entries !in 1..MAX_PALETTE_ENTRIES) return null
        val start = dataStart(bytes, chunk)
        val end = minOf(chunk + size, bytes.size)
        val bytesPer = if (format == FORMAT_RGBA8888) 4 else 2
        if (start + entries.toLong() * bytesPer > end) return null
        return IntArray(entries) { i -> directPixel(format, bytes, start + i * bytesPer) }
    }

    private fun decodeImage(bytes: ByteArray, chunk: Int, size: Int, palette: IntArray?): BmpImage? {
        if (chunk + BLOCK_HEADER + 32 > bytes.size) return null
        val format = bytes.u16(chunk + BLOCK_HEADER + 4)
        val swizzled = bytes.u16(chunk + BLOCK_HEADER + 6) == 1
        val width = bytes.u16(chunk + BLOCK_HEADER + 8)
        val height = bytes.u16(chunk + BLOCK_HEADER + 10)
        val bpp = bytes.u16(chunk + BLOCK_HEADER + 12)
        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) return null
        val expectedBpp = when (format) {
            FORMAT_RGBA8888 -> 32
            FORMAT_RGBA5650, FORMAT_RGBA5551, FORMAT_RGBA4444 -> 16
            FORMAT_INDEX4 -> 4
            FORMAT_INDEX8 -> 8
            else -> return null
        }
        if (bpp != expectedBpp) return null
        if (format >= FORMAT_INDEX4 && palette == null) return null

        val start = dataStart(bytes, chunk)
        val end = minOf(chunk + size, bytes.size)
        if (start >= end) return null

        // Rows pad to 16-byte columns; swizzled data additionally pads height to 8 rows.
        val pitch = ((width * bpp + 7) / 8 + 15) / 16 * 16
        val storedHeight = if (swizzled) (height + 7) / 8 * 8 else height
        if (start + pitch.toLong() * storedHeight > end) return null
        var pixels = bytes.copyOfRange(start, start + pitch * storedHeight)
        if (swizzled) pixels = unswizzle(pixels, pitch)

        val argb = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * pitch
            for (x in 0 until width) {
                argb[y * width + x] = when (format) {
                    FORMAT_INDEX8 -> palette!!.getOrNull(pixels[row + x].toInt() and 0xFF) ?: return null
                    FORMAT_INDEX4 -> {
                        val pair = pixels[row + x / 2].toInt() and 0xFF
                        val index = if (x % 2 == 0) pair and 0x0F else pair ushr 4
                        palette!!.getOrNull(index) ?: return null
                    }
                    FORMAT_RGBA8888 -> directPixel(format, pixels, row + x * 4)
                    else -> directPixel(format, pixels, row + x * 2)
                }
            }
        }
        return BmpImage(width, height, argb)
    }

    /** One direct-color pixel at [at] as packed ARGB. PSP stores red in the low bits. */
    private fun directPixel(format: Int, data: ByteArray, at: Int): Int {
        fun expand(v: Int, bits: Int): Int = v * 255 / ((1 shl bits) - 1)
        return when (format) {
            FORMAT_RGBA8888 -> {
                val r = data[at].toInt() and 0xFF
                val g = data[at + 1].toInt() and 0xFF
                val b = data[at + 2].toInt() and 0xFF
                val a = data[at + 3].toInt() and 0xFF
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            FORMAT_RGBA5650 -> {
                val v = data.u16(at)
                (0xFF shl 24) or (expand(v and 31, 5) shl 16) or
                    (expand((v ushr 5) and 63, 6) shl 8) or expand((v ushr 11) and 31, 5)
            }
            FORMAT_RGBA5551 -> {
                val v = data.u16(at)
                (if (v and 0x8000 != 0) 0xFF shl 24 else 0) or (expand(v and 31, 5) shl 16) or
                    (expand((v ushr 5) and 31, 5) shl 8) or expand((v ushr 10) and 31, 5)
            }
            else -> { // FORMAT_RGBA4444
                val v = data.u16(at)
                (expand((v ushr 12) and 15, 4) shl 24) or (expand(v and 15, 4) shl 16) or
                    (expand((v ushr 4) and 15, 4) shl 8) or expand((v ushr 8) and 15, 4)
            }
        }
    }

    /** PSP texture unswizzle: data is stored as consecutive 16-byte × 8-row blocks. */
    private fun unswizzle(data: ByteArray, pitch: Int): ByteArray {
        val height = data.size / pitch
        val out = ByteArray(data.size)
        val rowBlocks = pitch / 16
        for (y in 0 until height) {
            val blockRow = (y / 8) * rowBlocks
            val inBlockRow = (y % 8) * 16
            for (x in 0 until pitch) {
                out[y * pitch + x] = data[((x / 16) + blockRow) * 128 + inBlockRow + (x % 16)]
            }
        }
        return out
    }

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.i32(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
}
