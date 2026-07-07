package com.playfieldportal.themekit

/**
 * Decoded bitmap pixels, row-major top-down, one packed ARGB int per pixel (alpha always 0xFF
 * for BMP sources). Kept platform-neutral so both Android and desktop frontends can wrap it
 * in their own image types.
 */
class BmpImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(argb.size == width * height) { "pixel buffer ${argb.size} != ${width}x$height" }
    }

    operator fun get(x: Int, y: Int): Int = argb[y * width + x]
}

/**
 * Minimal decoder for the exact BMP variant official PSP themes embed as wallpaper:
 * uncompressed 24-bit BI_RGB (verified: every sampled `.ptf` wallpaper, including Sony's
 * own examples, is this variant — see docs/official-ptf-template.md). Pure Kotlin so the
 * shared module needs neither android.graphics nor java.awt.
 */
object Bmp {

    private const val FILE_HEADER_SIZE = 14
    private const val MIN_INFO_HEADER_SIZE = 40

    // Sanity cap on dimensions from untrusted files. Beyond OOM protection, this keeps the
    // Int arithmetic below (width * 3, width * height) safely inside 32 bits — absurd widths
    // would otherwise overflow the row stride BEFORE the bounds checks run.
    private const val MAX_DIMENSION = 8192

    /** Returns null when [bytes] is not an uncompressed 24-bit BMP. */
    fun decode(bytes: ByteArray): BmpImage? {
        if (bytes.size < FILE_HEADER_SIZE + MIN_INFO_HEADER_SIZE) return null
        if (bytes[0] != 'B'.code.toByte() || bytes[1] != 'M'.code.toByte()) return null

        val pixelOffset = bytes.u32(10)
        val width = bytes.i32(18)
        val rawHeight = bytes.i32(22)
        val bpp = bytes.u16(28)
        val compression = bytes.u32(30)

        if (width <= 0 || rawHeight == 0) return null
        if (width > MAX_DIMENSION || rawHeight > MAX_DIMENSION || rawHeight < -MAX_DIMENSION) return null
        if (bpp != 24 || compression != 0) return null

        val bottomUp = rawHeight > 0
        val height = if (bottomUp) rawHeight else -rawHeight
        val rowStride = (width * 3 + 3) and 0x3.inv() // rows padded to 4-byte boundaries
        if (pixelOffset < 0 || pixelOffset.toLong() + rowStride.toLong() * height > bytes.size) return null

        val argb = IntArray(width * height)
        for (row in 0 until height) {
            // BMP stores bottom row first unless height is negative.
            val srcRow = if (bottomUp) height - 1 - row else row
            var src = pixelOffset + srcRow * rowStride
            var dst = row * width
            repeat(width) {
                val b = bytes[src].toInt() and 0xFF
                val g = bytes[src + 1].toInt() and 0xFF
                val r = bytes[src + 2].toInt() and 0xFF
                argb[dst] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                src += 3
                dst++
            }
        }
        return BmpImage(width, height, argb)
    }

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(offset: Int): Int = i32(offset)

    private fun ByteArray.i32(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
}
