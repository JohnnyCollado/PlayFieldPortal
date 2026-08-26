package com.playfieldportal.feature.library.scanner

import com.playfieldportal.core.domain.model.GameRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Content-based region detection. Every detector reads the disc image bytes, never the filename —
 * the whole point is that "Parasite Eve II (USA) (Disc 1)" and "Parasite Eve II (Disc 2)" agree
 * on region because both .bin files say America.
 */
class DiscRegionDetectorsTest {

    private fun head(text: String): ByteArray = text.encodeToByteArray()

    private fun head(vararg blocks: ByteArray): ByteArray {
        val out = ByteArray(blocks.sumOf { it.size })
        var off = 0
        for (b in blocks) {
            b.copyInto(out, off)
            off += b.size
        }
        return out
    }

    // ── PS1 ──────────────────────────────────────────────────────────────────

    @Test
    fun `psx license string america is NTSC-U`() {
        // Simulated raw 2352-byte sector dump: license text lands ~0x24E0, not 2048-aligned.
        val padding = ByteArray(0x24E0)
        val bytes = head(padding, head("Licensed by Sony Computer Entertainment America"))
        assertEquals(GameRegion.NTSC_U, DiscRegionDetectors.detectPsx(bytes))
    }

    @Test
    fun `psx license string europe is PAL`() {
        val padding = ByteArray(0x2020)
        val bytes = head(padding, head("Licensed by Sony Computer Entertainment Europe"))
        assertEquals(GameRegion.PAL, DiscRegionDetectors.detectPsx(bytes))
    }

    @Test
    fun `psx license string inc is NTSC-J`() {
        val padding = ByteArray(0x2020)
        val bytes = head(padding, head("Licensed by Sony Computer Entertainment Inc."))
        assertEquals(GameRegion.NTSC_J, DiscRegionDetectors.detectPsx(bytes))
    }

    @Test
    fun `psx serial in SYSTEM CNF falls back to region`() {
        assertEquals(GameRegion.NTSC_U, DiscRegionDetectors.detectPsx(head("BOOT = cdrom:\\SLUS_004.17;1")))
        assertEquals(GameRegion.PAL, DiscRegionDetectors.detectPsx(head("BOOT = cdrom:\\SLES_123.45;1")))
        assertEquals(GameRegion.NTSC_J, DiscRegionDetectors.detectPsx(head("BOOT = cdrom:\\SLPS_999.01;1")))
    }

    @Test
    fun `psx garbage is null`() {
        assertNull(DiscRegionDetectors.detectPsx(head("this is not a playstation disc at all")))
    }

    // ── PS2 ──────────────────────────────────────────────────────────────────

    @Test
    fun `ps2 region line is detected`() {
        assertEquals(GameRegion.NTSC_U, DiscRegionDetectors.detectPs2(head("REGION=NTSC-U")))
        assertEquals(GameRegion.PAL, DiscRegionDetectors.detectPs2(head("REGION = PAL")))
        assertEquals(GameRegion.NTSC_J, DiscRegionDetectors.detectPs2(head("region=NTSC-J")))
    }

    @Test
    fun `ps2 falls back to serial`() {
        assertEquals(GameRegion.NTSC_U, DiscRegionDetectors.detectPs2(head("SLUS_204.86")))
        assertEquals(GameRegion.NTSC_J, DiscRegionDetectors.detectPs2(head("SLPS_250.02")))
    }

    // ── PSP ──────────────────────────────────────────────────────────────────

    @Test
    fun `psp product code prefix encodes region`() {
        assertEquals(GameRegion.NTSC_U, DiscRegionDetectors.detectPsp(head("ULUS-10345")))
        assertEquals(GameRegion.PAL, DiscRegionDetectors.detectPsp(head("ULES-00421")))
        assertEquals(GameRegion.NTSC_J, DiscRegionDetectors.detectPsp(head("ULJM-05555")))
        assertNull(DiscRegionDetectors.detectPsp(head("nothing here")))
    }

    // ── GameCube / Wii ───────────────────────────────────────────────────────

    private fun bootBin(id: String, regionField: Int): ByteArray {
        val bytes = ByteArray(0x60)
        id.encodeToByteArray().copyInto(bytes, 0)
        writeBe(bytes, 0x58, regionField)
        return bytes
    }

    private fun writeBe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value shr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }

    @Test
    fun `gc wii boot bin region field maps to region`() {
        assertEquals(GameRegion.NTSC_U, DiscRegionDetectors.detectBootBin(bootBin("GALE01", 1)))
        assertEquals(GameRegion.NTSC_J, DiscRegionDetectors.detectBootBin(bootBin("GALE01", 0)))
        assertEquals(GameRegion.PAL, DiscRegionDetectors.detectBootBin(bootBin("RSPE01", 2)))
    }

    @Test
    fun `gc wii game id region char is the fallback`() {
        assertEquals(GameRegion.NTSC_U, DiscRegionDetectors.detectBootBin(bootBin("GALE01", 0xFF)))
        assertEquals(GameRegion.PAL, DiscRegionDetectors.detectBootBin(bootBin("GALP01", 0xFF)))
        assertEquals(GameRegion.NTSC_J, DiscRegionDetectors.detectBootBin(bootBin("RSPJ01", 0xFF)))
    }

    @Test
    fun `gc wii garbage is null`() {
        assertNull(DiscRegionDetectors.detectBootBin(head("definitely not a boot.bin")))
    }

    // ── Saturn / Dreamcast / Sega CD ─────────────────────────────────────────

    private fun ipBin(magic: String, regionOffset: Int, regionChar: Char): ByteArray {
        val bytes = ByteArray(0x40)
        magic.encodeToByteArray().copyInto(bytes, 0)
        bytes[regionOffset] = regionChar.code.toByte()
        return bytes
    }

    @Test
    fun `dreamcast ip bin region char`() {
        assertEquals(GameRegion.NTSC_U, DiscRegionDetectors.detectIpBin(ipBin("SEGA SEGAKATANA ", 0x10, 'T')))
        assertEquals(GameRegion.PAL, DiscRegionDetectors.detectIpBin(ipBin("SEGA SEGAKATANA ", 0x10, 'E')))
        assertEquals(GameRegion.NTSC_J, DiscRegionDetectors.detectIpBin(ipBin("SEGA SEGAKATANA ", 0x10, 'J')))
    }

    @Test
    fun `saturn ip bin region char`() {
        assertEquals(GameRegion.NTSC_U, DiscRegionDetectors.detectIpBin(ipBin("SEGA SEGASATURN ", 0x20, 'U')))
        assertEquals(GameRegion.PAL, DiscRegionDetectors.detectIpBin(ipBin("SEGA SEGASATURN ", 0x20, 'E')))
    }

    @Test
    fun `ip bin without magic is null`() {
        assertNull(DiscRegionDetectors.detectIpBin(head("SEGA GENESIS ")))
    }

    // ── Xbox 360 ─────────────────────────────────────────────────────────────

    // Builds a minimal XEX2 header + optional headers with an Execution Info entry carrying the
    // given game region bitfield. Layout per XexTool: count at 0x18, each optional header is
    // size(4) type(4) data, size covers the whole entry including its 8-byte header.
    private fun xex(regionBits: Int): ByteArray {
        val executionInfo = ByteArray(0x24)  // media id, version, base, title id, platform, tables, format, disc, region
        writeLe(executionInfo, 0x1C, regionBits)
        val headerSize = 8 + executionInfo.size
        val total = 0x1C + headerSize
        val bytes = ByteArray(total)
        "XEX2".encodeToByteArray().copyInto(bytes, 0)
        writeLe(bytes, 0x18, 1)              // one optional header
        writeLe(bytes, 0x1C, headerSize)     // optional header size (incl. 8-byte header)
        writeLe(bytes, 0x20, 0x00000001)     // Execution Info type
        executionInfo.copyInto(bytes, 0x24)
        return bytes
    }

    private fun writeLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    @Test
    fun `x360 game region bitfield`() {
        assertEquals(GameRegion.NTSC_U, DiscRegionDetectors.detectX360(xex(0x01)))
        assertEquals(GameRegion.NTSC_J, DiscRegionDetectors.detectX360(xex(0x02)))
        assertEquals(GameRegion.PAL, DiscRegionDetectors.detectX360(xex(0x04)))
        // All-regions discs keep the same deterministic answer as their siblings.
        assertEquals(GameRegion.NTSC_U, DiscRegionDetectors.detectX360(xex(0x07)))
    }

    @Test
    fun `x360 non-xex bytes are null`() {
        assertNull(DiscRegionDetectors.detectX360(head("this is a gdi file, not an xex")))
    }

    // ── PS3 ──────────────────────────────────────────────────────────────────

    @Test
    fun `ps3 PARAM SFO title id prefix`() {
        assertEquals(GameRegion.NTSC_U, DiscRegionDetectors.detectPs3Sfo(head("BLUS30401")))
        assertEquals(GameRegion.PAL, DiscRegionDetectors.detectPs3Sfo(head("BLES00599")))
        assertEquals(GameRegion.NTSC_J, DiscRegionDetectors.detectPs3Sfo(head("BLJM60123")))
        assertNull(DiscRegionDetectors.detectPs3Sfo(head("no title here")))
    }
}
