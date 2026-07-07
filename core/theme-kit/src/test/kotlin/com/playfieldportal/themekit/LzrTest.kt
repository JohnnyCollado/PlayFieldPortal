package com.playfieldportal.themekit

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for the LZR decompressor. Stored-mode streams can be built hermetically;
 * real range-coded streams (no public compressor exists) are pinned by [GoldenPtfTest]
 * against firmware 3.70 theme files.
 */
class LzrTest {

    @Test
    fun `stored block round-trips`() {
        val data = ByteArray(1000) { (it * 31).toByte() }
        val stream = TestFixtures.lzrStored(data)
        val out = assertNotNull(Lzr.decompress(stream, 0, stream.size, maxOutput = data.size))
        assertContentEquals(data, out)
    }

    @Test
    fun `stored block at an offset inside a larger buffer`() {
        val data = "hello lzr".toByteArray()
        val stream = TestFixtures.lzrStored(data)
        val padded = ByteArray(64) + stream + ByteArray(16)
        val out = assertNotNull(Lzr.decompress(padded, 64, stream.size, maxOutput = 64))
        assertContentEquals(data, out)
    }

    @Test
    fun `stored block longer than maxOutput is rejected`() {
        val stream = TestFixtures.lzrStored(ByteArray(100))
        assertNull(Lzr.decompress(stream, 0, stream.size, maxOutput = 99))
    }

    @Test
    fun `stored block with a length claim past the input is rejected`() {
        val stream = TestFixtures.lzrStored(ByteArray(100))
        // Claim 200 bytes of stored data while only 100 follow.
        stream[3] = 0
        stream[4] = 200.toByte()
        assertNull(Lzr.decompress(stream, 0, stream.size, maxOutput = 1024))
    }

    @Test
    fun `truncated and garbage compressed streams fail cleanly, never hang`() {
        // Type 0 (compressed) with arbitrary bytes: must terminate with null or garbage
        // rejected downstream — decoding past the input end is the failure being pinned.
        val garbage = ByteArray(64) { (it * 7 + 1).toByte() }
        garbage[0] = 0 // compressed mode, literal-context shift 0
        Lzr.decompress(garbage, 0, garbage.size, maxOutput = 4096) // must return, null or not
        assertNull(Lzr.decompress(ByteArray(5), 0, 5, maxOutput = 16), "empty compressed body")
        assertNull(Lzr.decompress(ByteArray(3), 0, 3, maxOutput = 16), "shorter than the header")
    }

    @Test
    fun `absurd maxOutput claims are rejected up front`() {
        val stream = TestFixtures.lzrStored(ByteArray(8))
        assertNull(Lzr.decompress(stream, 0, stream.size, maxOutput = Lzr.MAX_OUTPUT_BYTES + 1))
        assertNull(Lzr.decompress(stream, 0, stream.size, maxOutput = -1))
    }
}
