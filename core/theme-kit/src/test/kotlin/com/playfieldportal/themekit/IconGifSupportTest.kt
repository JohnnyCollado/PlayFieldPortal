package com.playfieldportal.themekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [IconGifSupport] — the structural GIF probe and the animated-icon gate BOTH importers
 * share. The Studio validates against these numbers before a bundle ships; the handheld's own
 * gate (core-ui CustomIconLimits) enforces the same on device. These tests are the reason the
 * two cannot drift.
 */
class IconGifSupportTest {

    private fun gif(frames: Int, delayCs: Int = 10): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(64, 0, 64, 0, 0, 0, 0))
        repeat(frames) {
            out.write(0x21); out.write(0xF9); out.write(0x04)
            out.write(0x00)
            out.write(byteArrayOf(delayCs.toByte(), 0))
            out.write(0x00); out.write(0x00)
            out.write(0x2C)
            out.write(byteArrayOf(0, 0, 0, 0, 64, 0, 64, 0, 0))
            out.write(0x02); out.write(0x02); out.write(byteArrayOf(0x44, 0x01)); out.write(0x00)
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    @Test
    fun `signature detection`() {
        assertTrue(IconGifSupport.isGif(gif(1)))
        assertFalse(IconGifSupport.isGif("PNG-not-a-gif".toByteArray()))
        assertFalse(IconGifSupport.isGif(ByteArray(3)))
    }

    @Test
    fun `frame count matches the image descriptor count`() {
        assertEquals(1, IconGifSupport.countFrames(gif(1)))
        assertEquals(3, IconGifSupport.countFrames(gif(3)))
        assertEquals(0, IconGifSupport.countFrames("GIF89a-truncated".toByteArray()))
        assertEquals(0, IconGifSupport.countFrames(ByteArray(64)))
    }

    @Test
    fun `duration sums the GCE delays`() {
        assertEquals(300L, IconGifSupport.durationMs(gif(3, delayCs = 10)))
        assertEquals(700L, IconGifSupport.durationMs(gif(7, delayCs = 10)))
    }

    @Test
    fun `a two-frame gif passes every cap`() {
        assertNull(IconGifSupport.validateAnimated(width = 512, height = 512, frameCount = 2, durationMs = 10_000L))
    }

    @Test
    fun `each cap rejects by name`() {
        assertEquals(
            IconGifSupport.MSG_TOO_LARGE_RESOLUTION,
            IconGifSupport.validateAnimated(513, 512, 2, 1_000L),
        )
        assertEquals(
            IconGifSupport.MSG_TOO_MANY_FRAMES,
            IconGifSupport.validateAnimated(64, 64, 121, 1_000L),
        )
        assertEquals(
            IconGifSupport.MSG_TOO_LONG,
            IconGifSupport.validateAnimated(64, 64, 2, 10_001L),
        )
        assertEquals(
            IconGifSupport.MSG_UNDECODABLE,
            IconGifSupport.validateAnimated(0, 0, 2, 0L),
        )
    }

    @Test
    fun `single-frame gifs classify as stills by the same probe the launcher uses`() {
        // The >1 rule is the still/animated boundary on both sides.
        val single = gif(1)
        assertTrue(IconGifSupport.countFrames(single) <= 1)
    }
}
