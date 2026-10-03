package com.playfieldportal.studio.preview

import com.playfieldportal.studio.IconGifTestMedia
import com.playfieldportal.studio.StudioState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** TS-33: which icons animate, when, and how their frames are timed and bounded. */
class GifFramesTest {

    private fun state(vararg icons: Triple<String, ByteArray, String>) = StudioState(
        iconOverrides = icons.associate { it.first to it.second },
        iconExtensions = icons.associate { it.first to it.third },
    )

    @Test
    fun `the timeline loops over the frame delays`() {
        val t = GifTimeline(listOf(100, 200, 100))
        assertEquals(400, t.totalMs)
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 0, 0, 1), listOf(0L, 99L, 100L, 299L, 300L, 399L, 400L, 499L, 500L).map(t::frameAt))
        assertEquals(1, t.frameAt(400L * 1000 + 150), "a long-running clock still lands in the loop")
    }

    @Test
    fun `a single frame never advances`() {
        assertEquals(0, GifTimeline(listOf(100)).frameAt(12_345))
        assertEquals(0, GifTimeline(emptyList()).frameAt(5))
    }

    @Test
    fun `zero and tiny delays play at the conventional 100 ms`() {
        assertEquals(100, GifFrames.delayOrDefault(0))
        assertEquals(100, GifFrames.delayOrDefault(10))
        assertEquals(20, GifFrames.delayOrDefault(20))
        assertEquals(500, GifFrames.delayOrDefault(500))
    }

    @Test
    fun `the decoded frame count is bounded by the limits and the memory budget`() {
        assertEquals(120, GifFrames.frameLimit(64, 64), "small icons reach the format's frame cap")
        assertEquals(48, GifFrames.frameLimit(512, 512), "the largest allowed icon is memory-bound")
        assertEquals(2, GifFrames.frameLimit(8_000, 8_000), "never below two, so it still animates")
        assertTrue(GifFrames.frameLimit(512, 512).toLong() * 512 * 512 * 4 <= GifFrames.MEMORY_BUDGET_BYTES)
    }

    @Test
    fun `only animated gifs inside the limits are picked`() {
        val moving = IconGifTestMedia.animatedGif(frames = 3)
        val picked = GifFrames.animatedIcons(
            state(
                Triple("item_a", moving, "gif"),
                Triple("item_still_gif", IconGifTestMedia.singleFrameGif(), "gif"),
                Triple("item_png", byteArrayOf(1, 2, 3), "png"),
                Triple("item_mislabelled", moving, "png"),
                Triple("item_too_big", IconGifTestMedia.animatedGif(frames = 3, width = 600, height = 600), "gif"),
                Triple("item_too_many", IconGifTestMedia.animatedGif(frames = 130), "gif"),
                Triple("item_too_long", IconGifTestMedia.animatedGif(frames = 3, delayCs = 500), "gif"),
            ),
        )
        assertEquals(setOf("item_a"), picked.keys)
    }

    @Test
    fun `console art gifs animate too`() {
        val moving = IconGifTestMedia.animatedGif(frames = 3)
        val picked = GifFrames.animatedIcons(
            StudioState(sysiconOverrides = mapOf("sysicon_ps3" to moving), sysiconExtensions = mapOf("sysicon_ps3" to "gif")),
        )
        assertEquals(setOf("sysicon_ps3"), picked.keys)
    }

    @Test
    fun `an icon animates only when live and focused`() {
        val live = PreviewLiveSpec(iconGifs = mapOf("item_a" to IconGifTestMedia.animatedGif()))
        assertTrue(GifFrames.animates("item_a", focused = true, live = live))
        assertFalse(GifFrames.animates("item_a", focused = false, live = live), "unfocused shows frame 1")
        assertFalse(GifFrames.animates("item_b", focused = true, live = live), "no animation for this icon")
        assertFalse(GifFrames.animates("item_a", focused = true, live = null), "a static frame never animates")
    }

    /**
     * A GIF Skia can actually decode (the shared fixture only has a valid *structure*): a 4-colour
     * palette, each frame one flat colour, LZW written as `CLEAR, c, c` triples so no code ever
     * outgrows its 3-bit width.
     */
    private fun decodableGif(frames: Int, delayCs: Int, size: Int = 16): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun le(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        le(size); le(size)
        out.write(0x91); out.write(0); out.write(0) // global table, 4 colours
        listOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00).forEach { out.write(it shr 16); out.write(it shr 8); out.write(it) }
        repeat(frames) { f ->
            out.write(0x21); out.write(0xF9); out.write(4); out.write(0x00); le(delayCs); out.write(0); out.write(0)
            out.write(0x2C); le(0); le(0); le(size); le(size); out.write(0)
            out.write(2) // LZW minimum code size
            val bits = java.io.ByteArrayOutputStream()
            var acc = 0
            var n = 0
            fun code(c: Int) {
                acc = acc or (c shl n); n += 3
                while (n >= 8) { bits.write(acc and 0xFF); acc = acc shr 8; n -= 8 }
            }
            repeat(size * size / 2) { code(4); code(f % 4); code(f % 4) }
            code(5)
            if (n > 0) bits.write(acc and 0xFF)
            val data = bits.toByteArray()
            var pos = 0
            while (pos < data.size) {
                val len = minOf(255, data.size - pos)
                out.write(len); out.write(data, pos, len); pos += len
            }
            out.write(0)
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    @Test
    fun `skia decodes the frames and their delays`() {
        val animation = assertNotNull(GifFrames.decode(decodableGif(frames = 3, delayCs = 20)))
        assertEquals(3, animation.frames.size)
        assertEquals(600, animation.timeline.totalMs)
        assertEquals(0, animation.timeline.frameAt(0))
        assertEquals(2, animation.timeline.frameAt(450))
    }

    @Test
    fun `a still or garbage is not an animation`() {
        assertEquals(null, GifFrames.decode(IconGifTestMedia.singleFrameGif()))
        assertEquals(null, GifFrames.decode(byteArrayOf(1, 2, 3, 4)))
    }
}
