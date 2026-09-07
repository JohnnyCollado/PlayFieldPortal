package com.playfieldportal.core.ui.icons

import java.io.File

/**
 * Counts the frames in a GIF by walking its block structure — the cheap classifier behind the
 * "single-frame GIF is stored and treated as static — no decoder is ever started for it" rule
 * (CustomIconStore.load for user picks, XMBViewModel.loadThemeIconOverrides for theme icons).
 *
 * This is a STRUCTURAL walk only: it skips extension and image-data sub-blocks without
 * decoding LZW, so it works on any container bytes without allocating frame bitmaps. Returns
 * 0 for non-GIF or truncated data — callers treat anything under 2 as static, so a corrupt
 * file degrades to a Still rather than an error.
 */
object GifFrameProbe {

    /** Reads [file] and counts its frames; 0 on any IO failure. */
    fun countFrames(file: File): Int = runCatching {
        countFrames(file.inputStream().use { it.readBytes() })
    }.getOrDefault(0)

    /** Counts frames in raw GIF bytes; 0 for non-GIF or structurally broken data. */
    fun countFrames(bytes: ByteArray): Int {
        if (bytes.size < 13) return 0
        val sig = String(bytes, 0, 6, Charsets.US_ASCII)
        if (sig != "GIF87a" && sig != "GIF89a") return 0
        // Header (6) + logical screen descriptor (7). Skip the global color table if present.
        var i = 13
        if (bytes[10].toInt() and 0x80 != 0) {
            i += 3 * (1 shl ((bytes[10].toInt() and 0x07) + 1))
        }
        var frames = 0
        while (i < bytes.size) {
            when (bytes[i].toInt() and 0xFF) {
                0x21 -> {
                    // Extension: separator + label, then length-prefixed sub-blocks to a 0 terminator.
                    i += 2
                    while (i < bytes.size) {
                        val len = bytes[i].toInt() and 0xFF
                        i += 1 + len
                        if (len == 0) break
                    }
                }
                0x2C -> {
                    // Image descriptor = one frame: separator + 9-byte descriptor, an optional
                    // local color table, the LZW min-code-size byte, then data sub-blocks.
                    frames++
                    if (i + 9 >= bytes.size) return frames
                    val packed = bytes[i + 9].toInt() and 0xFF
                    i += 10
                    if (packed and 0x80 != 0) i += 3 * (1 shl ((packed and 0x07) + 1))
                    if (i >= bytes.size) return frames
                    i += 1 // LZW min code size
                    while (i < bytes.size) {
                        val len = bytes[i].toInt() and 0xFF
                        i += 1 + len
                        if (len == 0) break
                    }
                }
                0x3B -> return frames // trailer — the clean end of a well-formed GIF
                else -> return frames // unknown block: treat what we counted as the answer
            }
        }
        return frames
    }
}
