package com.playfieldportal.feature.achievements.match

import java.security.MessageDigest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaRomHasherTest {

    private fun md5(b: ByteArray) =
        MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun `full-md5 systems hash the whole file`() {
        val rom = byteArrayOf(1, 2, 3, 4, 5)
        assertEquals(md5(rom), RaRomHasher.hash("gba", rom))
    }

    @Test
    fun `nes strips the 16-byte ines header`() {
        val payload = ByteArray(100) { (it % 7).toByte() }
        val rom = "NES".toByteArray() + byteArrayOf(0x1A) + ByteArray(12) + payload
        assertEquals(md5(payload), RaRomHasher.hash("nes", rom))
    }

    @Test
    fun `nes without a header hashes the whole file`() {
        val rom = ByteArray(50) { it.toByte() }
        assertEquals(md5(rom), RaRomHasher.hash("nes", rom))
    }

    @Test
    fun `snes strips a 512-byte copier header`() {
        val payload = ByteArray(1024) { (it % 5).toByte() }
        val rom = ByteArray(512) + payload // size 1536, %1024 == 512
        assertEquals(md5(payload), RaRomHasher.hash("snes", rom))
    }

    @Test
    fun `snes without a copier header hashes the whole file`() {
        val rom = ByteArray(1024) { it.toByte() } // %1024 == 0
        assertEquals(md5(rom), RaRomHasher.hash("snes", rom))
    }

    @Test
    fun `n64 dump formats all hash the same as z64`() {
        val z64 = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val v64 = byteArrayOf(0x37, 0x80.toByte(), 0x40, 0x12, 0xBB.toByte(), 0xAA.toByte(), 0xDD.toByte(), 0xCC.toByte())
        val n64 = byteArrayOf(0x40, 0x12, 0x37, 0x80.toByte(), 0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte())

        val expected = md5(z64)
        assertEquals(expected, RaRomHasher.hash("n64", z64))
        assertEquals(expected, RaRomHasher.hash("n64", v64))
        assertEquals(expected, RaRomHasher.hash("n64", n64))
    }

    @Test
    fun `disc systems are unsupported`() {
        assertNull(RaRomHasher.hash("psx", byteArrayOf(1, 2, 3)))
        assertFalse(RaRomHasher.isSupported("psx"))
        assertTrue(RaRomHasher.isSupported("snes"))
    }

    @Test
    fun `console ids map for supported cartridge systems`() {
        assertEquals(7, RaConsole.idFor("nes"))
        assertEquals(3, RaConsole.idFor("snes"))
        assertEquals(5, RaConsole.idFor("gba"))
        assertNull(RaConsole.idFor("psx"))
    }
}
