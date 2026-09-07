package com.playfieldportal.themekit

/**
 * Animated-GIF support for theme icons, shared by BOTH sides of the feature.
 *
 * The launcher's import gate lives in core-ui ([com.playfieldportal.core.ui.icons.CustomIconLimits],
 * Android-only, BitmapFactory-based) and its animation classifier in
 * [com.playfieldportal.core.ui.icons.GifFrameProbe]; the desktop Theme Studio must apply the
 * SAME numbers and the SAME frame/duration rules — a Studio-authored theme is installed without
 * re-validation on the handheld, so this object is where the truth lives. core-ui's types alias
 * these constants, and the Studio validates against them directly.
 *
 * The structural walk never LZW-decodes: it reads signatures, the logical screen descriptor, and
 * skips extension/image-data sub-blocks by length, so it works on untrusted container bytes
 * without allocating frame bitmaps.
 */
object IconGifSupport {

    // ── The numbers (the launcher's CustomIconLimits aliases these) ──────────

    /** Largest template is 256px; 512 gives headroom. Stills downscale; GIFs must fit. */
    const val MAX_DIMENSION = 512

    const val MAX_FRAMES = 120

    /** A looping accent, not a video. */
    const val MAX_DURATION_MS = 10_000L

    // ── Rejection strings, surfaced verbatim by both importers ───────────────

    const val MSG_TOO_LARGE_RESOLUTION = "Image is too large — 512px or smaller for a GIF"
    const val MSG_TOO_MANY_FRAMES = "GIF has too many frames — 120 or fewer"
    const val MSG_TOO_LONG = "GIF is too long — 10 seconds or less"
    const val MSG_TOO_LARGE_BYTES = "File is too large — under 8 MB"
    const val MSG_UNDECODABLE = "Couldn't read that image — try a different file"

    // ── Structure ────────────────────────────────────────────────────────────

    /** True when [bytes] carry a GIF87a/GIF89a signature. */
    fun isGif(bytes: ByteArray): Boolean {
        if (bytes.size < 6) return false
        val sig = String(bytes, 0, 6, Charsets.US_ASCII)
        return sig == "GIF87a" || sig == "GIF89a"
    }

    /** The GIF logical screen descriptor's width/height, or null when the bytes are not a GIF. */
    fun logicalScreenSize(bytes: ByteArray): Pair<Int, Int>? {
        if (!isGif(bytes) || bytes.size < 10) return null
        val width = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
        val height = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        return width to height
    }

    /**
     * Counts frames by walking the block structure — one per image descriptor (0x2C). Returns 0
     * for non-GIF or truncated data; callers treat anything under 2 as static, so a corrupt
     * file degrades to a still rather than an error.
     */
    fun countFrames(bytes: ByteArray): Int {
        if (!isGif(bytes) || bytes.size < 13) return 0
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

    /**
     * Total animation duration in milliseconds: the sum of every Graphics Control Extension's
     * delay (centiseconds, little-endian). 0 when no GCE carries a delay — callers treat that
     * as "unknown", not as zero-length, so a delay-less GIF is not rejected as too short.
     */
    fun durationMs(bytes: ByteArray): Long {
        if (!isGif(bytes) || bytes.size < 13) return 0
        var i = 13
        if (bytes[10].toInt() and 0x80 != 0) {
            i += 3 * (1 shl ((bytes[10].toInt() and 0x07) + 1))
        }
        var centiseconds = 0L
        while (i < bytes.size) {
            when (bytes[i].toInt() and 0xFF) {
                0x21 -> {
                    val label = bytes.getOrNull(i + 1)?.toInt()?.and(0xFF) ?: return centiseconds * 10
                    i += 2
                    if (label == 0xF9) {
                        // GCE: first sub-block is 4 bytes — packed, delay u16 LE, transparent index.
                        val len = bytes.getOrNull(i)?.toInt()?.and(0xFF) ?: 0
                        if (len >= 4 && i + 4 < bytes.size) {
                            centiseconds += (bytes[i + 2].toInt() and 0xFF) or ((bytes[i + 3].toInt() and 0xFF) shl 8)
                        }
                    }
                    while (i < bytes.size) {
                        val len = bytes[i].toInt() and 0xFF
                        i += 1 + len
                        if (len == 0) break
                    }
                }
                0x2C -> {
                    if (i + 9 >= bytes.size) return centiseconds * 10
                    val packed = bytes[i + 9].toInt() and 0xFF
                    i += 10
                    if (packed and 0x80 != 0) i += 3 * (1 shl ((packed and 0x07) + 1))
                    if (i >= bytes.size) return centiseconds * 10
                    i += 1 // LZW min code size
                    while (i < bytes.size) {
                        val len = bytes[i].toInt() and 0xFF
                        i += 1 + len
                        if (len == 0) break
                    }
                }
                0x3B -> return centiseconds * 10
                else -> return centiseconds * 10
            }
        }
        return centiseconds * 10
    }

    /**
     * The launcher-parity gate for an ANIMATED gif (multi-frame): null when it passes, else the
     * rejection message. Single-frame gifs never reach this — they are authored as stills.
     */
    fun validateAnimated(width: Int, height: Int, frameCount: Int, durationMs: Long): String? = when {
        width <= 0 || height <= 0 -> MSG_UNDECODABLE
        width > MAX_DIMENSION || height > MAX_DIMENSION -> MSG_TOO_LARGE_RESOLUTION
        frameCount > MAX_FRAMES -> MSG_TOO_MANY_FRAMES
        durationMs > MAX_DURATION_MS -> MSG_TOO_LONG
        else -> null
    }
}
