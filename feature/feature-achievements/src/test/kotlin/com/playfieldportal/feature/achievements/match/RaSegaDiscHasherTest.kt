package com.playfieldportal.feature.achievements.match

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests the Sega CD / Saturn 512-byte header hash across both physical sector layouts (cooked 2048
 * and raw 2352 Mode 1) and both magic strings.
 */
class RaSegaDiscHasherTest {

    private class MemSource(private val data: ByteArray) : DiscImage.SeekableSource {
        override fun readFully(offset: Long, dest: ByteArray, len: Int): Int {
            if (offset >= data.size) return 0
            val n = minOf(len.toLong(), data.size - offset).toInt()
            System.arraycopy(data, offset.toInt(), dest, 0, n)
            return n
        }
        override fun close() {}
    }

    private fun md5Of(b: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun cookedImage(magic: String): ByteArray {
        val img = ByteArray(2048)
        val m = magic.toByteArray(Charsets.US_ASCII)
        System.arraycopy(m, 0, img, 0, m.size)
        for (i in m.size until 512) img[i] = ((i * 7) and 0xFF).toByte()
        return img
    }

    private fun rawMode1Image(magic: String): ByteArray {
        val img = ByteArray(2352)
        // 12-byte sync 00 FF*10 00, then address, then mode byte 1 at offset 15; user data at 16.
        img[0] = 0
        for (i in 1..10) img[i] = 0xFF.toByte()
        img[11] = 0
        img[15] = 1
        val m = magic.toByteArray(Charsets.US_ASCII)
        System.arraycopy(m, 0, img, 16, m.size)
        for (i in m.size until 512) img[16 + i] = ((i * 3) and 0xFF).toByte()
        return img
    }

    @Test
    fun `sega cd cooked image hashes the 512-byte header`() {
        val img = cookedImage("SEGADISCSYSTEM  ")
        val image = DiscImage.openRawCd(MemSource(img))
        assertEquals(md5Of(img.copyOfRange(0, 512)), RaSegaDiscHasher.hash(image))
    }

    @Test
    fun `saturn raw 2352 image hashes the 512-byte header at the mode-1 offset`() {
        val img = rawMode1Image("SEGA SEGASATURN ")
        val image = DiscImage.openRawCd(MemSource(img))
        assertEquals(md5Of(img.copyOfRange(16, 16 + 512)), RaSegaDiscHasher.hash(image))
    }

    @Test
    fun `a disc without a Sega magic is rejected`() {
        val img = ByteArray(2048) // no magic
        val image = DiscImage.openRawCd(MemSource(img))
        assertNull(RaSegaDiscHasher.hash(image))
    }
}
