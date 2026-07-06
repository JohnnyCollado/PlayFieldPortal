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
     * payload is [wallpaperLeadIn] junk bytes followed by the zlib'd [wallpaperBmp] —
     * mirroring real files, where the zlib stream starts partway into the slot.
     */
    fun buildPtf(
        name: String,
        firmware: String,
        wallpaperBmp: ByteArray,
        wallpaperLeadIn: Int = 32,
    ): ByteArray {
        val compressed = zlib(wallpaperBmp)
        val descriptorOffset = 0x120
        val dataOffset = 0x140
        val slotSize = wallpaperLeadIn + compressed.size
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

        compressed.copyInto(file, dataOffset + wallpaperLeadIn)
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
