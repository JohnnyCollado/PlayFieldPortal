package com.playfieldportal.studio

import java.io.ByteArrayOutputStream

/**
 * Hand-built GIF89a fixtures, constructed block-by-block per the GIF89a spec. No LZW pixel data
 * is needed: the probe under test ([com.playfieldportal.themekit.IconGifSupport]) walks the
 * CONTAINER structure, and Skia renders whatever frame 1 it can from the (blank) image blocks —
 * both are exercised by structure, not by pixels.
 */
object IconGifTestMedia {

    /** A GIF89a with [frames] image descriptors, each preceded by a GCE delaying [delayCs] centiseconds. */
    fun animatedGif(frames: Int = 3, delayCs: Int = 10, width: Int = 64, height: Int = 64): ByteArray {
        val out = ByteArrayOutputStream()
        // Header + logical screen descriptor (no global color table, background 0, aspect 0).
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(width.toLo(), width.toHi(), height.toLo(), height.toHi(), 0x00, 0x00, 0x00))
        repeat(frames) {
            // Graphic Control Extension: label F9, block size 4, packed, delay LE, transparent 0, terminator.
            out.write(0x21); out.write(0xF9); out.write(0x04)
            out.write(0x00)
            out.write(byteArrayOf(delayCs.toLo(), delayCs.toHi()))
            out.write(0x00); out.write(0x00)
            // Image descriptor: separator, geometry (at 0,0, full size), no local color table,
            // not interlaced, then a minimal LZW stream (min code size + empty blocks + terminator).
            out.write(0x2C)
            out.write(byteArrayOf(0, 0, 0, 0, width.toLo(), width.toHi(), height.toLo(), height.toHi(), 0x00))
            out.write(0x02) // LZW min code size
            out.write(0x02) // one sub-block of 2 bytes
            out.write(byteArrayOf(0x44, 0x01.toByte()))
            out.write(0x00) // block terminator
        }
        out.write(0x3B) // trailer
        return out.toByteArray()
    }

    /** A structurally valid single-frame GIF (classifies as a still). */
    fun singleFrameGif(): ByteArray = animatedGif(frames = 1)

    private fun Int.toLo(): Byte = (this and 0xFF).toByte()
    private fun Int.toHi(): Byte = ((this shr 8) and 0xFF).toByte()
}
