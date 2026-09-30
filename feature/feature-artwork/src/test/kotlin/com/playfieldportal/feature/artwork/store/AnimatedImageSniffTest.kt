package com.playfieldportal.feature.artwork.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which files keep their animation when cropped (framed at draw time) instead of being baked to a
 * still PNG. A GIF always goes the animated way — framing a single-frame GIF at draw time is still
 * correct, just unnecessary. A WebP only when its VP8X header says it is animated.
 */
class AnimatedImageSniffTest {

    private fun bytes(vararg parts: Any): ByteArray = parts.flatMap { part ->
        when (part) {
            is String -> part.toByteArray(Charsets.US_ASCII).toList()
            is Int -> listOf(part.toByte())
            else -> error("unsupported part")
        }
    }.toByteArray()

    // RIFF <size> WEBP VP8X <chunk size> <flags> …
    private fun webpVp8x(flags: Int) = bytes("RIFF", 0, 0, 0, 0, "WEBP", "VP8X", 10, 0, 0, 0, flags, 0, 0, 0)

    @Test
    fun `a GIF is animated`() {
        assertTrue(ImageFormat.isAnimated(bytes("GIF89a", 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun `a WebP with the animation flag is animated`() {
        assertTrue(ImageFormat.isAnimated(webpVp8x(flags = 0x02)))
        assertTrue("other flags alongside it", ImageFormat.isAnimated(webpVp8x(flags = 0x12)))
    }

    @Test
    fun `an extended WebP without the flag is still`() {
        assertFalse(ImageFormat.isAnimated(webpVp8x(flags = 0x10)))   // alpha only
    }

    @Test
    fun `a simple WebP, a PNG and a JPEG are still`() {
        assertFalse(ImageFormat.isAnimated(bytes("RIFF", 0, 0, 0, 0, "WEBP", "VP8 ", 0, 0, 0, 0, 0)))
        assertFalse(ImageFormat.isAnimated(bytes(0x89, "PNG", 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 0)))
        assertFalse(ImageFormat.isAnimated(bytes(0xFF, 0xD8, 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun `a header too short to read the flag is still`() {
        assertFalse(ImageFormat.isAnimated(bytes("RIFF", 0, 0, 0, 0, "WEBP", "VP8X")))
    }
}
