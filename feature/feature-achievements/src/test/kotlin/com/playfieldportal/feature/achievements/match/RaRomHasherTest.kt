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
    fun `disc systems have no rom hasher`() {
        // RaRomHasher doesn't hash disc images; they match via the title fallback instead.
        assertNull(RaRomHasher.hash("psx", byteArrayOf(1, 2, 3)))
        assertFalse(RaRomHasher.isSupported("psx"))
        assertFalse(RaRomHasher.isSupported("ps2"))
        assertTrue(RaRomHasher.isSupported("snes"))
    }

    @Test
    fun `console ids map for cartridge systems`() {
        assertEquals(7, RaConsole.idFor("nes"))
        assertEquals(3, RaConsole.idFor("snes"))
        assertEquals(5, RaConsole.idFor("gba"))
        assertEquals(18, RaConsole.idFor("nds"))
    }

    @Test
    fun `console ids map for disc systems so the title fallback can link them`() {
        assertEquals(12, RaConsole.idFor("psx"))
        assertEquals(21, RaConsole.idFor("ps2"))
        assertEquals(41, RaConsole.idFor("psp"))
        assertEquals(16, RaConsole.idFor("gc"))
        assertEquals(19, RaConsole.idFor("wii"))
        // RA has no achievements for these — they must stay unmatched.
        assertNull(RaConsole.idFor("ps3"))
        assertNull(RaConsole.idFor("psvita"))
        assertNull(RaConsole.idFor("n3ds"))
        assertNull(RaConsole.idFor("x360"))
    }

    private fun putU32LE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    @Test
    fun `nds hashes header, arm9, arm7 and icon in order`() {
        val rom = ByteArray(0xE00)
        for (i in 0 until 0x160) rom[i] = (i and 0xFF).toByte()   // header pattern
        putU32LE(rom, 0x20, 0x200)                                // arm9 offset
        putU32LE(rom, 0x2C, 0x40)                                 // arm9 size
        putU32LE(rom, 0x30, 0x300)                                // arm7 offset
        putU32LE(rom, 0x3C, 0x20)                                 // arm7 size
        putU32LE(rom, 0x68, 0x400)                                // icon offset
        for (i in 0x200 until 0x240) rom[i] = 0xA1.toByte()       // arm9 (64 bytes)
        for (i in 0x300 until 0x320) rom[i] = 0xB7.toByte()       // arm7 (32 bytes)
        for (i in 0x400 until 0xE00) rom[i] = 0xC9.toByte()       // icon (0xA00 bytes)

        val expected = md5(
            rom.copyOfRange(0, 0x160) +
                rom.copyOfRange(0x200, 0x240) +
                rom.copyOfRange(0x300, 0x320) +
                rom.copyOfRange(0x400, 0xE00),
        )
        assertEquals(expected, RaRomHasher.hash("nds", rom))
    }

    @Test
    fun `nds icon block is zero-padded when the rom is short`() {
        // Icon points near EOF with fewer than 0xA00 bytes remaining; the rest is zero-filled.
        val rom = ByteArray(0x300)
        putU32LE(rom, 0x20, 0x160); putU32LE(rom, 0x2C, 0x10)
        putU32LE(rom, 0x30, 0x170); putU32LE(rom, 0x3C, 0x10)
        putU32LE(rom, 0x68, 0x200)                               // icon off; only 0x100 bytes remain
        for (i in 0x200 until 0x300) rom[i] = 0x5A.toByte()

        val icon = rom.copyOfRange(0x200, 0x300) + ByteArray(0xA00 - 0x100)
        val expected = md5(rom.copyOfRange(0, 0x160) + rom.copyOfRange(0x160, 0x170) + rom.copyOfRange(0x170, 0x180) + icon)
        assertEquals(expected, RaRomHasher.hash("nds", rom))
    }
}
