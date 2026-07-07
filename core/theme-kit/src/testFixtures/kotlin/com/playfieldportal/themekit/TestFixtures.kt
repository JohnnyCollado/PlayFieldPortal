package com.playfieldportal.themekit

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/** Builders for synthetic theme files so format tests run hermetically on CI. */
object TestFixtures {

    /** Uncompressed 24-bit bottom-up BMP with pixels from [argbAt] (x, y are top-down). */
    fun buildBmp(width: Int, height: Int, argbAt: (x: Int, y: Int) -> Int): ByteArray {
        val rowStride = (width * 3 + 3) and 0x3.inv()
        val pixelBytes = rowStride * height
        val fileSize = 54 + pixelBytes
        val out = ByteArray(fileSize)

        out[0] = 'B'.code.toByte(); out[1] = 'M'.code.toByte()
        out.putU32(2, fileSize)
        out.putU32(10, 54)          // pixel data offset
        out.putU32(14, 40)          // BITMAPINFOHEADER size
        out.putU32(18, width)
        out.putU32(22, height)      // positive -> bottom-up
        out.putU16(26, 1)           // planes
        out.putU16(28, 24)          // bpp
        out.putU32(34, pixelBytes)

        for (y in 0 until height) {
            val dstRow = 54 + (height - 1 - y) * rowStride // bottom-up storage
            for (x in 0 until width) {
                val argb = argbAt(x, y)
                out[dstRow + x * 3] = (argb and 0xFF).toByte()          // B
                out[dstRow + x * 3 + 1] = (argb shr 8 and 0xFF).toByte()  // G
                out[dstRow + x * 3 + 2] = (argb shr 16 and 0xFF).toByte() // R
            }
        }
        return out
    }

    /**
     * Minimal GIM container: root/picture chunks, an optional RGBA8888 palette, and one
     * image block. [argbAt] supplies pixels; [indexed] stores them as index8 through a
     * palette built from the distinct colors, otherwise as direct RGBA8888. [swizzle]
     * stores pixel data in the PSP's 16-byte x 8-row block order.
     */
    fun buildGim(
        width: Int,
        height: Int,
        indexed: Boolean = true,
        swizzle: Boolean = false,
        argbAt: (x: Int, y: Int) -> Int,
    ): ByteArray {
        val pixels = IntArray(width * height) { argbAt(it % width, it / width) }
        val bpp = if (indexed) 8 else 32
        val pitch = ((width * bpp + 7) / 8 + 15) / 16 * 16
        val storedHeight = if (swizzle) (height + 7) / 8 * 8 else height

        val palette = if (indexed) pixels.distinct().also { check(it.size <= 256) } else emptyList()
        var data = ByteArray(pitch * storedHeight)
        for (y in 0 until height) for (x in 0 until width) {
            if (indexed) {
                data[y * pitch + x] = palette.indexOf(pixels[y * width + x]).toByte()
            } else {
                val p = pixels[y * width + x]
                data[y * pitch + x * 4] = (p shr 16).toByte()      // R
                data[y * pitch + x * 4 + 1] = (p shr 8).toByte()   // G
                data[y * pitch + x * 4 + 2] = p.toByte()           // B
                data[y * pitch + x * 4 + 3] = (p shr 24).toByte()  // A
            }
        }
        if (swizzle) data = swizzle(data, pitch)

        fun block(id: Int, format: Int, w: Int, h: Int, blockBpp: Int, payload: ByteArray): ByteArray {
            val chunk = ByteArray(16 + 64 + payload.size)
            chunk.putU16(0, id)
            chunk.putU32(4, chunk.size)
            chunk.putU32(8, chunk.size)
            chunk.putU16(16, 48)              // data-header size
            chunk.putU16(20, format)
            chunk.putU16(22, if (id == 0x04 && swizzle) 1 else 0)
            chunk.putU16(24, w)
            chunk.putU16(26, h)
            chunk.putU16(28, blockBpp)
            chunk.putU16(44, 64)              // pixel-data offset from chunk+16
            payload.copyInto(chunk, 16 + 64)
            return chunk
        }

        val paletteChunk = if (indexed) {
            val bytes = ByteArray(palette.size * 4)
            palette.forEachIndexed { i, p ->
                bytes[i * 4] = (p shr 16).toByte(); bytes[i * 4 + 1] = (p shr 8).toByte()
                bytes[i * 4 + 2] = p.toByte(); bytes[i * 4 + 3] = (p shr 24).toByte()
            }
            block(0x05, 3, palette.size, 1, 32, bytes)
        } else ByteArray(0)
        val imageChunk = block(0x04, if (indexed) 5 else 3, width, height, bpp, data)

        val total = 16 + 16 + 16 + paletteChunk.size + imageChunk.size
        val out = ByteArray(total)
        "MIG.00.1PSP".toByteArray(Charsets.US_ASCII).copyInto(out)
        out.putU16(16, 0x02); out.putU32(20, total - 16)          // root
        out.putU16(32, 0x03); out.putU32(36, total - 32)          // picture
        paletteChunk.copyInto(out, 48)
        imageChunk.copyInto(out, 48 + paletteChunk.size)
        return out
    }

    /** PSP texture swizzle (inverse of the decoder's unswizzle). */
    private fun swizzle(data: ByteArray, pitch: Int): ByteArray {
        val height = data.size / pitch
        val out = ByteArray(data.size)
        val rowBlocks = pitch / 16
        for (y in 0 until height) for (x in 0 until pitch) {
            out[((x / 16) + (y / 8) * rowBlocks) * 128 + (y % 8) * 16 + (x % 16)] = data[y * pitch + x]
        }
        return out
    }

    /**
     * Stored-mode LZR stream (negative type byte): 5-byte header + raw data + 1 pad byte.
     * Sony's LZR range-coder has no public compressor, but the stored mode exercises the
     * same entry point and header handling, keeping method-1 PTF tests hermetic; real
     * compressed streams are covered by the golden-file tests.
     */
    fun lzrStored(data: ByteArray): ByteArray {
        val out = ByteArray(5 + data.size + 1)
        out[0] = (-1).toByte()
        out[1] = (data.size ushr 24).toByte()
        out[2] = (data.size ushr 16).toByte()
        out[3] = (data.size ushr 8).toByte()
        out[4] = data.size.toByte()
        data.copyInto(out, 5)
        return out
    }

    /** zlib-compress (default settings produce the 0x78 0x9C header real PTFs carry). */
    fun zlib(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    /**
     * Minimal structurally-valid official PTF: header, one wallpaper slot (ID 1) whose
     * payload is the real 32-byte payload header (resource type 4, [compressionMethod],
     * compressed/uncompressed sizes) followed by the compressed [wallpaperBmp] —
     * zlib for method 2 (fw 3.80+), a stored-mode LZR stream for method 1 (fw 3.70).
     */
    fun buildPtf(
        name: String,
        firmware: String,
        wallpaperBmp: ByteArray,
        compressionMethod: Int = 2,
    ): ByteArray {
        val compressed = if (compressionMethod == 1) lzrStored(wallpaperBmp) else zlib(wallpaperBmp)
        val descriptorOffset = 0x120
        val dataOffset = 0x140
        val headerSize = 32
        val slotSize = headerSize + compressed.size
        val file = ByteArray(dataOffset + slotSize)

        // magic "\0PTF"
        file[1] = 'P'.code.toByte(); file[2] = 'T'.code.toByte(); file[3] = 'F'.code.toByte()
        name.toByteArray(Charsets.ISO_8859_1).copyInto(file, 0x08, 0, minOf(name.length, 16))
        firmware.toByteArray(Charsets.ISO_8859_1).copyInto(file, 0xB8, 0, minOf(firmware.length, 8))

        file.putU32(0x100, descriptorOffset)             // slot table: one pointer, zero-terminated
        file.putU16(descriptorOffset, 1)                  // slot id 1 = wallpaper
        file.putU16(descriptorOffset + 2, 1)              // subtype (as real files use)
        file.putU32(descriptorOffset + 4, slotSize)
        file.putU32(descriptorOffset + 8, dataOffset)

        // 32-byte payload header, as in real slot payloads.
        file.putU16(dataOffset + 4, 4)                    // resource type 4 = wallpaper
        file.putU16(dataOffset + 6, compressionMethod)    // 1 = LZR, 2 = zlib
        file.putU32(dataOffset + 8, compressed.size)
        file.putU32(dataOffset + 12, wallpaperBmp.size)

        compressed.copyInto(file, dataOffset + headerSize)
        return file
    }

    private fun ByteArray.putU16(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = (value shr 8 and 0xFF).toByte()
    }

    private fun ByteArray.putU32(offset: Int, value: Int) {
        putU16(offset, value and 0xFFFF)
        putU16(offset + 2, value ushr 16)
    }
}
