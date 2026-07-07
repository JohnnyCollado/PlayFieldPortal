package com.playfieldportal.themekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GimTest {

    private val red = 0xFFE01030.toInt()
    private val translucent = 0x8020FF40.toInt()

    @Test
    fun `decodes an index8 gim with alpha preserved`() {
        val gim = TestFixtures.buildGim(20, 10) { x, y -> if ((x + y) % 2 == 0) red else translucent }
        val image = assertNotNull(Gim.decode(gim))
        assertEquals(20, image.width)
        assertEquals(10, image.height)
        assertEquals(red, image[0, 0])
        assertEquals(translucent, image[1, 0])
        assertEquals(translucent, image[0, 1])
    }

    // Position-dependent color that stays within an index8 palette (251 distinct values).
    private fun swatch(x: Int, y: Int, width: Int): Int {
        val v = (x + y * width) % 251
        return (0xFF shl 24) or (v shl 16) or ((v * 7) and 0xFF shl 8) or ((v * 13) and 0xFF)
    }

    @Test
    fun `decodes a swizzled index8 gim`() {
        // Wider than one 16-byte block and taller than one 8-row block, so real
        // block reshuffling happens — position-dependent colors catch any misplacement.
        val gim = TestFixtures.buildGim(48, 24, swizzle = true) { x, y -> swatch(x, y, 48) }
        val image = assertNotNull(Gim.decode(gim))
        for (y in 0 until 24) for (x in 0 until 48 step 5) {
            assertEquals(swatch(x, y, 48), image[x, y], "pixel ($x,$y)")
        }
    }

    @Test
    fun `decodes a direct-color rgba8888 gim`() {
        val gim = TestFixtures.buildGim(8, 6, indexed = false, swizzle = true) { x, y ->
            if (x == y) translucent else red
        }
        val image = assertNotNull(Gim.decode(gim))
        assertEquals(translucent, image[3, 3])
        assertEquals(red, image[4, 3])
    }

    @Test
    fun `isGim sniffs the magic`() {
        assertTrue(Gim.isGim(TestFixtures.buildGim(4, 4) { _, _ -> red }))
        assertTrue(!Gim.isGim("BM not a gim".toByteArray()))
        assertTrue(!Gim.isGim(ByteArray(4)))
    }

    @Test
    fun `garbage, truncation, and hostile headers are rejected`() {
        assertNull(Gim.decode(ByteArray(64) { 3 }))
        val valid = TestFixtures.buildGim(20, 10) { _, _ -> red }
        assertNull(Gim.decode(valid.copyOf(valid.size / 2)), "truncated mid-image")
        // Image block claiming absurd dimensions must be rejected before allocation.
        val hostile = TestFixtures.buildGim(20, 10) { _, _ -> red }
        val imageChunk = findChunk(hostile, 0x04)
        hostile[imageChunk + 16 + 8] = 0xFF.toByte() // width low byte
        hostile[imageChunk + 16 + 9] = 0xFF.toByte() // width high byte -> 65535
        assertNull(Gim.decode(hostile))
    }

    @Test
    fun `indexed image without a palette is rejected`() {
        val gim = TestFixtures.buildGim(20, 10) { _, _ -> red }
        // Corrupt the palette chunk id so only the image block remains readable.
        val palette = findChunk(gim, 0x05)
        gim[palette] = 0x77
        assertNull(Gim.decode(gim))
    }

    private fun findChunk(gim: ByteArray, id: Int): Int {
        var o = 16
        while (o + 16 <= gim.size) {
            val chunkId = (gim[o].toInt() and 0xFF) or ((gim[o + 1].toInt() and 0xFF) shl 8)
            if (chunkId == id) return o
            if (chunkId == 0x02 || chunkId == 0x03) { o += 16; continue }
            val size = (gim[o + 4].toInt() and 0xFF) or ((gim[o + 5].toInt() and 0xFF) shl 8) or
                ((gim[o + 6].toInt() and 0xFF) shl 16) or ((gim[o + 7].toInt() and 0xFF) shl 24)
            if (size < 16) break
            o += size
        }
        error("chunk $id not found")
    }
}
