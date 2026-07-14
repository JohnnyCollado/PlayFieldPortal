package com.playfieldportal.feature.achievements.match

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Structural tests for the GameCube / Wii raw-offset hashing. They build minimal synthetic images
 * and assert the hasher reads exactly the regions rcheevos hashes, in order — the byte layout is the
 * thing that must stay byte-for-byte compatible with RetroAchievements. (Cross-checking against RA's
 * live DB needs a real disc image, mirroring how PSX/PS2 were validated on the device.)
 */
class RaNintendoDiscHasherTest {

    /** In-memory seekable source over a fixed byte array. */
    private class MemSource(private val data: ByteArray) : DiscImage.SeekableSource {
        override fun readFully(offset: Long, dest: ByteArray, len: Int): Int {
            if (offset >= data.size) return 0
            val n = minOf(len.toLong(), data.size - offset).toInt()
            System.arraycopy(data, offset.toInt(), dest, 0, n)
            return n
        }
        override fun close() {}
    }

    private fun md5Hex(vararg regions: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        regions.forEach { md.update(it) }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun ByteArray.putBe32(off: Int, value: Long) {
        this[off] = (value ushr 24).toByte()
        this[off + 1] = (value ushr 16).toByte()
        this[off + 2] = (value ushr 8).toByte()
        this[off + 3] = value.toByte()
    }

    private fun ByteArray.fill(off: Int, len: Int, seed: Int): ByteArray {
        for (i in 0 until len) this[off + i] = ((seed + i) and 0xFF).toByte()
        return this
    }

    @Test
    fun `gamecube hashes the partition header and each non-empty dol segment`() {
        val img = ByteArray(0x6000)
        // GameCube magic at 0x1C.
        img.putBe32(0x1c, 0xC2339F3DL)

        // Apploader body/trailer sizes -> header_size = 0x2440 + 0x20 + body + trailer.
        val body = 0x40L
        val trailer = 0x20L
        img.putBe32(0x2440 + 0x14, body)
        img.putBe32(0x2440 + 0x14 + 4, trailer)
        val headerSize = (0x2440 + 0x20 + body + trailer).toInt() // 0x24C0

        // Boot DOL offset lives at header[0x420].
        val dolOffset = 0x3000
        img.putBe32(0x420, dolOffset.toLong())

        // Segment table (0xD8) at dolOffset: seg0 (code) and seg7 (first data segment).
        img.putBe32(dolOffset + 0x00 + 0 * 4, 0x4000)  // seg0 offset
        img.putBe32(dolOffset + 0x90 + 0 * 4, 0x100)   // seg0 size
        img.putBe32(dolOffset + 0x00 + 7 * 4, 0x5000)  // seg7 offset
        img.putBe32(dolOffset + 0x90 + 7 * 4, 0x80)    // seg7 size

        img.fill(0x4000, 0x100, seed = 0x11)
        img.fill(0x5000, 0x80, seed = 0x22)

        val expected = md5Hex(
            img.copyOfRange(0, headerSize),
            img.copyOfRange(0x4000, 0x4100),
            img.copyOfRange(0x5000, 0x5080),
        )
        assertEquals(expected, RaNintendoDiscHasher.hash("gc", MemSource(img)))
    }

    @Test
    fun `gamecube rejects an image without the magic word`() {
        assertNull(RaNintendoDiscHasher.hash("gc", MemSource(ByteArray(0x3000))))
    }

    @Test
    fun `wii encrypted disc hashes header, region, tmd and cluster payloads verbatim`() {
        val img = ByteArray(0x70000)
        // Wii disc magic at 0x18; byte 0x61 == 0 marks an encrypted (retail) image.
        img.putBe32(0x18, 0x5D1C9EA3L)
        img[0x61] = 0

        // One partition group with one non-Update partition.
        img.putBe32(0x40000, 1)                 // group 0 count
        img.putBe32(0x40004, 0x50000L shr 2)    // group 0 table offset (>>2)
        val base = 0x60000
        img.putBe32(0x50000, base.toLong() shr 2) // partition offset (>>2)
        img.putBe32(0x50004, 0)                   // type 0 (not the Update partition)

        // TMD: size 0x100 at +0x2A4, offset (>>2, base-relative) at +0x2A8.
        img.putBe32(base + 0x2A4, 0x100)
        img.putBe32(base + 0x2A8, 0x1000L shr 2)
        // Partition data: the offset at +0x2B8 is an ABSOLUTE file offset (>>2); size (>>2) at +0x2BC.
        val partData = 0x68000
        img.putBe32(base + 0x2B8, partData.toLong() shr 2)
        img.putBe32(base + 0x2BC, 0x8000L shr 2)              // one 0x8000 cluster

        img.fill(base + 0x1000, 0x100, seed = 0x33)           // TMD bytes (base-relative)
        val clusterAt = partData + 0x400                      // skips the 0x400 crypto header
        img.fill(clusterAt, 0x7C00, seed = 0x44)              // cluster payload

        val expected = md5Hex(
            img.copyOfRange(0, 0x80),                         // main header
            img.copyOfRange(0x4E000, 0x4E004),               // region code
            img.copyOfRange(base + 0x1000, base + 0x1100),   // TMD
            img.copyOfRange(clusterAt, clusterAt + 0x7C00),  // encrypted cluster payload
        )
        assertEquals(expected, RaNintendoDiscHasher.hash("wii", MemSource(img)))
    }
}
