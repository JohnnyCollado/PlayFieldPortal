package com.playfieldportal.themekit

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Theme files arrive via SAF from arbitrary sources — these tests pin the guards that keep
 * crafted files from crashing the app (decompression bombs, absurd dimensions).
 */
class HostileInputTest {

    @Test
    fun `zlib bomb in the wallpaper slot is rejected, not inflated to OOM`() {
        // ~64MB of zeros deflates to ~64KB — inflating it must stop at the cap, not allocate it all.
        val bombPayload = TestFixtures.zlib(ByteArray(64 * 1024 * 1024))
        // A structurally-valid PTF whose wallpaper stream is the bomb: reuse the fixture builder
        // by handing it a fake "BMP" the size of the bomb source is impractical — instead build
        // the container manually around the pre-deflated payload.
        val small = TestFixtures.buildPtf("Bomb", "6.20", TestFixtures.buildBmp(2, 2) { _, _ -> 0 })
        // Replace the wallpaper stream: rebuild with the bomb spliced after the 32-byte lead-in.
        val dataOffset = 0x140
        val leadIn = 32
        val file = ByteArray(dataOffset + leadIn + bombPayload.size)
        small.copyInto(file, 0, 0, dataOffset) // header + slot table (sizes below overwrite)
        // Fix the slot size to cover the bomb payload.
        val slotSize = leadIn + bombPayload.size
        file[0x124] = (slotSize and 0xFF).toByte()
        file[0x125] = (slotSize shr 8 and 0xFF).toByte()
        file[0x126] = (slotSize shr 16 and 0xFF).toByte()
        file[0x127] = (slotSize shr 24 and 0xFF).toByte()
        bombPayload.copyInto(file, dataOffset + leadIn)

        val theme = assertNotNull(PtfParser.parse(file))
        assertNull(theme.wallpaper, "bomb wallpaper must be rejected")
    }

    // ── Malformed resource pointers ───────────────────────────────────────────
    // u32 returns a signed Int, so 0xFFFFFFFF decodes to -1. The guard was
    // `if (ptr + 12 > bytes.size) break`, and -1 + 12 = 11 passes it — the next line then calls
    // bytes.u16(-1) and throws ArrayIndexOutOfBounds. PtfThemeImporter calls parse() bare inside
    // a viewModelScope launch, so a crafted .ptf picked in Settings > Themes crashed the app.

    private fun ptfWithSlotPointer(pointer: Long): ByteArray {
        val file = ByteArray(0x140)
        file[0] = 0; file[1] = 'P'.code.toByte(); file[2] = 'T'.code.toByte(); file[3] = 'F'.code.toByte()
        val table = 0x100
        file[table]     = (pointer and 0xFF).toByte()
        file[table + 1] = (pointer shr 8 and 0xFF).toByte()
        file[table + 2] = (pointer shr 16 and 0xFF).toByte()
        file[table + 3] = (pointer shr 24 and 0xFF).toByte()
        return file
    }

    @Test
    fun `a slot pointer of 0xFFFFFFFF does not throw`() {
        val theme = PtfParser.parse(ptfWithSlotPointer(0xFFFFFFFFL))

        // Parsing may yield a theme with no usable slots, or null — either is fine. Crashing is not.
        assertNull(theme?.wallpaper)
    }

    @Test
    fun `a slot pointer with the high bit set does not throw`() {
        val theme = PtfParser.parse(ptfWithSlotPointer(0x80000000L))

        assertNull(theme?.wallpaper)
    }

    @Test
    fun `a slot pointer past the end of the file is skipped`() {
        val theme = PtfParser.parse(ptfWithSlotPointer(0x7FFFFFF0L))

        assertNull(theme?.wallpaper)
    }

    @Test
    fun `a truncated gim does not throw`() {
        val gim = ByteArray(40)
        "MIG.00.1PSP".toByteArray(Charsets.ISO_8859_1).copyInto(gim)

        assertNull(Gim.decode(gim))
    }

    @Test
    fun `a gim chunk size that overflows the walk offset does not throw`() {
        // The chunk walk advanced with `offset += size` where size came from a signed i32. A size
        // near Int.MAX_VALUE overflows offset to a negative number, and the loop condition
        // `offset + 16 <= bytes.size` is then satisfied by a negative index.
        val gim = ByteArray(64)
        "MIG.00.1PSP".toByteArray(Charsets.ISO_8859_1).copyInto(gim)
        // Chunk at 16: id = 0x99 (unknown, so the walk just advances), size = 0x7FFFFFF0.
        gim[16] = 0x99.toByte(); gim[17] = 0
        gim[20] = 0xF0.toByte(); gim[21] = 0xFF.toByte(); gim[22] = 0xFF.toByte(); gim[23] = 0x7F

        assertNull(Gim.decode(gim))
    }

    @Test
    fun `a gim chunk size of zero terminates instead of spinning`() {
        val gim = ByteArray(64)
        "MIG.00.1PSP".toByteArray(Charsets.ISO_8859_1).copyInto(gim)
        gim[16] = 0x99.toByte()
        // size = 0 — advancing by it would never terminate.

        assertNull(Gim.decode(gim))
    }

    @Test
    fun `bmp with absurd dimensions is rejected before allocation`() {
        // Hand-build a BMP header claiming a ~800M-pixel-wide image (width*3 overflows Int).
        val header = ByteArray(64)
        header[0] = 'B'.code.toByte(); header[1] = 'M'.code.toByte()
        header.putU32(10, 54)          // pixel offset
        header.putU32(14, 40)          // info header size
        header.putU32(18, 800_000_000) // width
        header.putU32(22, 2)           // height
        header.putU16(26, 1)
        header.putU16(28, 24)          // 24bpp
        assertNull(Bmp.decode(header))

        // And a merely-huge-but-plausible one still over the cap.
        header.putU32(18, 20_000)
        assertNull(Bmp.decode(header))
    }

    @Test
    fun `pfptheme zip bomb entry is rejected`() {
        // A bundle whose wallpaper entry deflates ~64MB of zeros from a tiny file.
        val zip = ByteArrayOutputStream().also { baos ->
            ZipOutputStream(baos).use { z ->
                z.putNextEntry(ZipEntry("manifest.json"))
                z.write("""{"manifest":"pfptheme","schemaVersion":1,"name":"Bomb","accentColor":"#FFFFFF"}""".toByteArray())
                z.closeEntry()
                z.putNextEntry(ZipEntry("wallpaper.png"))
                val chunk = ByteArray(1024 * 1024)
                repeat(64) { z.write(chunk) }
                z.closeEntry()
            }
        }.toByteArray()
        assertNull(PfpThemeCodec.read(zip))
    }

    private fun ByteArray.putU16(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = (value shr 8 and 0xFF).toByte()
    }

    private fun ByteArray.putU32(offset: Int, value: Int) {
        putU16(offset, value and 0xFFFF)
        putU16(offset + 2, value ushr 16)
    }
}
